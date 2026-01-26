package org.onetwoone.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * Service to monitor and limit battery charging level.
 * Requires root access to control charging via sysfs.
 */
public class BatteryLimitService extends Service {
    private static final String TAG = "BatteryLimit";
    private static final String CHANNEL_ID = "battery_limit_channel";
    private static final int NOTIFICATION_ID = 4;

    // Charging control paths (varies by device)
    // We try ALL paths to handle both USB and AC charging
    private static final String[][] CHARGING_PATHS = {
        // {path, value_to_disable, value_to_enable}
        {"/sys/class/power_supply/battery/input_suspend", "1", "0"},  // inverted logic
        {"/sys/class/power_supply/battery/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/usb/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/dc/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/ac/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/main/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/pc_port/charging_enabled", "0", "1"},
    };

    // Hysteresis to avoid rapid on/off (5% below limit to resume)
    private static final int HYSTERESIS = 5;

    // CRITICAL: Never disable charging below this level to prevent brick
    private static final int CRITICAL_BATTERY_LEVEL = 20;

    // SAFETY: Force re-enable charging if battery drops below this (deep discharge protection)
    private static final int DEEP_DISCHARGE_LEVEL = 50;

    private int chargeLimit = 60;  // Default 60%
    private boolean chargingDisabled = false;
    private java.util.List<String[]> activeChargingPaths = new java.util.ArrayList<>();
    private int lastBatteryLevel = -1;
    private boolean receiverRegistered = false;
    private Handler mainHandler;

    // Watchdog to re-enforce charging state (Android/kernel resets it frequently)
    // Must be aggressive (5s) because kernel resets charging_enabled constantly
    private static final int ENFORCE_INTERVAL_MS = 5000;  // 5 seconds
    private Runnable enforceRunnable;

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int percent = (level * 100) / scale;

                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                      status == BatteryManager.BATTERY_STATUS_FULL);

                handleBatteryLevel(percent, isCharging);
            } catch (Exception e) {
                Log.e(TAG, "Error in battery receiver: " + e.getMessage());
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        // CRITICAL: Start foreground IMMEDIATELY to avoid ANR/crash
        // Android gives only 5 seconds after startForegroundService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundWithNotification();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground: " + e.getMessage());
                showToast("Battery service error: " + e.getMessage());
                stopSelf();
                return;
            }
        }

        // Load saved limit (fast, do before slow operations)
        try {
            SharedPreferences prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE);
            chargeLimit = prefs.getInt("battery_limit", 60);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load prefs: " + e.getMessage());
            chargeLimit = 60;
        }

        // Do slow initialization in background
        new Thread(() -> {
            try {
                // Find ALL working charging control paths
                findChargingPaths();

                // Get current battery level to determine correct state
                IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = registerReceiver(null, batteryFilter);
                int level = -1;
                if (batteryStatus != null) {
                    int rawLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    level = (rawLevel * 100) / scale;
                }

                // Restore correct charging state based on battery level and limit
                if (level >= 0) {
                    Log.i(TAG, "Service restart: battery=" + level + "%, limit=" + chargeLimit + "%");

                    if (level < CRITICAL_BATTERY_LEVEL) {
                        // SAFETY: Battery critically low - always enable charging
                        Log.w(TAG, "SAFETY on restart: Battery " + level + "% < critical, enabling charging");
                        forceEnableCharging();
                        chargingDisabled = false;
                    } else if (chargeLimit >= 100) {
                        // No limit - enable charging
                        Log.d(TAG, "No limit on restart, enabling charging");
                        forceEnableCharging();
                        chargingDisabled = false;
                    } else if (level >= chargeLimit) {
                        // Above limit - disable charging
                        Log.i(TAG, "Battery " + level + "% >= limit " + chargeLimit + "%, disabling charging");
                        setCharging(false);
                    } else if (level <= (chargeLimit - HYSTERESIS)) {
                        // Below threshold - enable charging
                        Log.i(TAG, "Battery " + level + "% below threshold, enabling charging");
                        setCharging(true);
                    } else {
                        // In hysteresis zone - read actual state and sync flag
                        boolean actuallyCharging = isActuallyCharging();
                        chargingDisabled = !actuallyCharging;
                        Log.d(TAG, "In hysteresis zone, syncing with actual state: disabled=" + chargingDisabled);
                    }
                } else {
                    // Can't determine level - be safe and enable
                    Log.w(TAG, "Cannot determine battery level on restart, enabling charging for safety");
                    forceEnableCharging();
                    chargingDisabled = false;
                }

                // Register battery receiver on main thread
                mainHandler.post(() -> {
                    try {
                        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                        registerReceiver(batteryReceiver, filter);
                        receiverRegistered = true;
                        Log.d(TAG, "Battery receiver registered");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to register receiver: " + e.getMessage());
                    }
                });

                // Update notification with actual status
                mainHandler.post(this::updateNotification);

                // Start periodic enforcement watchdog
                startEnforceWatchdog();

                Log.d(TAG, "BatteryLimitService initialized, limit: " + chargeLimit + "%, disabled: " + chargingDisabled);

            } catch (Exception e) {
                Log.e(TAG, "Error during initialization: " + e.getMessage());
                showToast("Battery limit init error: " + e.getMessage());
            }
        }, "BatteryLimitInit").start();
    }

    /**
     * Start watchdog that periodically checks and corrects charging state.
     * This is critical for recovering from crashes/restarts and kernel resets.
     * Philosophy: Better to charge to 100% on glitch than to have dead battery.
     */
    private void startEnforceWatchdog() {
        enforceRunnable = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    try {
                        if (activeChargingPaths.isEmpty()) {
                            return;  // No control available
                        }

                        // Get current battery level
                        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                        Intent batteryStatus = registerReceiver(null, filter);
                        if (batteryStatus == null) return;

                        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        int percent = (level * 100) / scale;

                        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        boolean systemSaysCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                                      status == BatteryManager.BATTERY_STATUS_FULL);

                        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                        boolean isPluggedIn = plugged != 0;

                        // SAFETY: Critical battery - always enable
                        if (percent < CRITICAL_BATTERY_LEVEL && isPluggedIn) {
                            Log.w(TAG, "WATCHDOG: Battery critical (" + percent + "%), enabling charging!");
                            setCharging(true);
                            return;
                        }

                        // SAFETY: Deep discharge protection
                        if (percent < DEEP_DISCHARGE_LEVEL && isPluggedIn && !systemSaysCharging) {
                            Log.w(TAG, "WATCHDOG: Battery low (" + percent + "%), enabling charging!");
                            setCharging(true);
                            return;
                        }

                        // Normal operation: enforce limit
                        if (chargeLimit < 100) {
                            if (percent >= chargeLimit && systemSaysCharging) {
                                // Above limit but charging - disable it
                                Log.d(TAG, "WATCHDOG: Battery " + percent + "% >= limit " + chargeLimit + "%, disabling");
                                setCharging(false);
                            } else if (percent <= (chargeLimit - HYSTERESIS) && !systemSaysCharging && isPluggedIn) {
                                // Below threshold and not charging - enable it
                                Log.d(TAG, "WATCHDOG: Battery " + percent + "% < threshold, enabling");
                                setCharging(true);
                            } else if (chargingDisabled && percent >= chargeLimit) {
                                // Re-enforce disabled state
                                setCharging(false);
                            }
                        } else {
                            // No limit - ensure charging enabled if plugged
                            if (isPluggedIn && !systemSaysCharging) {
                                Log.d(TAG, "WATCHDOG: No limit, enabling charging");
                                setCharging(true);
                            }
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Error in enforce watchdog: " + e.getMessage());
                    }
                }, "WatchdogCheck").start();

                // Schedule next run
                if (mainHandler != null) {
                    mainHandler.postDelayed(this, ENFORCE_INTERVAL_MS);
                }
            }
        };

        mainHandler.postDelayed(enforceRunnable, ENFORCE_INTERVAL_MS);
        Log.d(TAG, "Enforce watchdog started (interval: " + ENFORCE_INTERVAL_MS + "ms)");
    }

    /**
     * Stop enforcement watchdog
     */
    private void stopEnforceWatchdog() {
        if (enforceRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(enforceRunnable);
            enforceRunnable = null;
            Log.d(TAG, "Enforce watchdog stopped");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Ensure foreground (in case onCreate was skipped somehow)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundWithNotification();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground in onStartCommand: " + e.getMessage());
            }
        }

        // Update limit if provided
        if (intent != null && intent.hasExtra("limit")) {
            chargeLimit = intent.getIntExtra("limit", 60);
            try {
                getSharedPreferences("gateway_prefs", MODE_PRIVATE).edit()
                    .putInt("battery_limit", chargeLimit)
                    .apply();
            } catch (Exception e) {
                Log.e(TAG, "Failed to save limit: " + e.getMessage());
            }
            Log.d(TAG, "Battery limit set to: " + chargeLimit + "%");
            updateNotification();

            // If limit is 100%, re-enable charging
            if (chargeLimit >= 100 && chargingDisabled) {
                setCharging(true);
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Stop enforcement watchdog
        stopEnforceWatchdog();

        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
                receiverRegistered = false;
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister receiver: " + e.getMessage());
            }
        }

        // Re-enable charging on service stop (in background to not block)
        new Thread(() -> {
            try {
                setCharging(true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to re-enable charging on destroy: " + e.getMessage());
            }
        }).start();

        Log.d(TAG, "BatteryLimitService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void findChargingPaths() {
        activeChargingPaths.clear();

        for (String[] pathInfo : CHARGING_PATHS) {
            String path = pathInfo[0];
            try {
                String result = RootHelper.execRoot("cat " + path + " 2>/dev/null");
                if (result != null && !result.trim().isEmpty()) {
                    activeChargingPaths.add(pathInfo);
                    Log.d(TAG, "Found charging control: " + path + " (value: " + result.trim() + ")");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error checking path " + path + ": " + e.getMessage());
            }
        }

        if (activeChargingPaths.isEmpty()) {
            Log.w(TAG, "No charging control path found - battery limit may not work on this device");
            showToast("No charging control found - battery limit disabled");
        } else {
            Log.i(TAG, "Found " + activeChargingPaths.size() + " charging control path(s)");
        }
    }

    /**
     * SAFETY: Force enable charging on ALL known paths.
     * Called on service start to recover from crashes.
     */
    private void forceEnableCharging() {
        Log.i(TAG, "SAFETY: Force enabling charging on all paths");

        // Reset internal state
        chargingDisabled = false;

        // Force enable on ALL paths, not just the active one
        try {
            RootHelper.execRoot("echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null");
            RootHelper.execRoot("echo 1 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null");
            RootHelper.execRoot("echo 1 > /sys/class/power_supply/usb/charging_enabled 2>/dev/null");
            Log.i(TAG, "SAFETY: Charging force-enabled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to force enable charging: " + e.getMessage());
        }
    }

    private void showToast(String message) {
        if (mainHandler != null) {
            mainHandler.post(() -> {
                try {
                    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to show toast: " + e.getMessage());
                }
            });
        }
    }

    private void handleBatteryLevel(int percent, boolean isCharging) {
        try {
            // Only log when level changes
            if (percent != lastBatteryLevel) {
                lastBatteryLevel = percent;
                Log.d(TAG, "Battery: " + percent + "%, charging: " + isCharging +
                           ", limit: " + chargeLimit + "%, disabled: " + chargingDisabled);
            }

            if (chargeLimit >= 100) {
                // No limit - ensure charging is enabled
                if (chargingDisabled) {
                    Log.d(TAG, "Limit is 100%, re-enabling charging");
                    setChargingAsync(true);
                }
                return;
            }

            // SAFETY: Never disable charging if battery is critically low
            if (percent < CRITICAL_BATTERY_LEVEL && chargingDisabled) {
                Log.w(TAG, "SAFETY: Battery critically low (" + percent + "%), force enabling charging!");
                setChargingAsync(true);
                updateNotification();
                return;
            }

            // SAFETY: Deep discharge protection - force re-enable if battery too low
            if (percent < DEEP_DISCHARGE_LEVEL && chargingDisabled && isCharging) {
                Log.w(TAG, "DEEP DISCHARGE PROTECTION: Battery " + percent + "% < " + DEEP_DISCHARGE_LEVEL + "%, re-enabling charging!");
                setChargingAsync(true);
                updateNotification();
                return;
            }

            if (percent >= chargeLimit && !chargingDisabled && percent >= CRITICAL_BATTERY_LEVEL) {
                // At or above limit - disable charging (only if above critical level)
                Log.i(TAG, "Battery at " + percent + "%, stopping charging (limit: " + chargeLimit + "%)");
                setChargingAsync(false);
                updateNotification();
            } else if (percent <= (chargeLimit - HYSTERESIS) && chargingDisabled) {
                // Below threshold with hysteresis - re-enable charging
                Log.i(TAG, "Battery at " + percent + "%, resuming charging (threshold: " + (chargeLimit - HYSTERESIS) + "%)");
                setChargingAsync(true);
                updateNotification();
            } else if (chargingDisabled && percent >= chargeLimit) {
                // Battery level changed but still above limit - re-enforce disabled state
                // Kernel often resets charging_enabled, so we need to keep setting it
                setChargingAsync(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling battery level: " + e.getMessage());
        }
    }

    /**
     * Check if charging is actually enabled in sysfs (not just our internal flag).
     * Reads real state from kernel to detect if Android/kernel changed it.
     *
     * @return true if charging appears to be enabled in hardware
     */
    private boolean isActuallyCharging() {
        try {
            // Check input_suspend (inverted logic: 0 = charging allowed, 1 = suspended)
            String suspend = RootHelper.execRoot("cat /sys/class/power_supply/battery/input_suspend 2>/dev/null");
            if (suspend != null && suspend.trim().equals("0")) {
                return true;  // input_suspend=0 means charging is allowed
            }

            // Also check charging_enabled
            String enabled = RootHelper.execRoot("cat /sys/class/power_supply/battery/charging_enabled 2>/dev/null");
            if (enabled != null && enabled.trim().equals("1")) {
                // charging_enabled=1 but if input_suspend=1, still not charging
                return suspend != null && suspend.trim().equals("0");
            }

            // Default: assume not charging if can't determine
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read actual charging state: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set charging state asynchronously to avoid blocking the main thread.
     */
    private void setChargingAsync(boolean enable) {
        new Thread(() -> setCharging(enable), "SetCharging").start();
    }

    private synchronized void setCharging(boolean enable) {
        if (activeChargingPaths.isEmpty()) {
            Log.w(TAG, "No charging paths available");
            return;
        }

        try {
            // Apply to ALL active paths to handle both USB and AC charging
            for (String[] pathInfo : activeChargingPaths) {
                String path = pathInfo[0];
                String disableValue = pathInfo[1];
                String enableValue = pathInfo[2];
                String value = enable ? enableValue : disableValue;

                String cmd = "echo " + value + " > " + path;
                RootHelper.execRoot(cmd);
                Log.d(TAG, "Set " + path + " = " + value);
            }

            chargingDisabled = !enable;
            Log.i(TAG, "Charging " + (enable ? "enabled" : "disabled") + " on " + activeChargingPaths.size() + " path(s)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set charging: " + e.getMessage());
        }
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Battery Limit Service",
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Monitors and limits battery charging");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create notification channel: " + e.getMessage());
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void updateNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.notify(NOTIFICATION_ID, buildNotification());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update notification: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

            String text;
            if (chargeLimit >= 100) {
                text = "No limit (charging normally)";
            } else if (activeChargingPaths.isEmpty()) {
                text = "Limit: " + chargeLimit + "% [NO CONTROL]";
            } else {
                text = "Limit: " + chargeLimit + "%" + (chargingDisabled ? " [PAUSED]" : " [active]");
            }

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            return builder
                .setContentTitle("Battery Charge Limit")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build notification: " + e.getMessage());
            // Return minimal notification to avoid crash
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            return builder
                .setContentTitle("Battery Limit")
                .setContentText("Running...")
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .build();
        }
    }

    public int getChargeLimit() {
        return chargeLimit;
    }

    public boolean isChargingDisabled() {
        return chargingDisabled;
    }
}
