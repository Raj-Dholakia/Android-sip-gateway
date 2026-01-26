package org.onetwoone.gateway;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Watchdog that runs independently of BatteryLimitService.
 * Ensures charging is enabled if battery is critically low.
 * Runs every 15 minutes via WorkManager.
 */
public class BatteryWatchdog extends Worker {
    private static final String TAG = "BatteryWatchdog";
    private static final String WORK_NAME = "battery_watchdog";
    private static final int CRITICAL_LEVEL = 25;  // Higher than service's 20% for extra safety

    public BatteryWatchdog(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        // Get current battery level
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);

        if (batteryStatus == null) {
            Log.w(TAG, "Could not get battery status");
            return Result.success();
        }

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = (level * 100) / scale;

        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                              status == BatteryManager.BATTERY_STATUS_FULL);

        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean isPluggedIn = plugged != 0;

        Log.d(TAG, "Watchdog check: " + percent + "%, charging: " + isCharging + ", plugged: " + isPluggedIn);

        // CRITICAL: If battery is low and plugged in but not charging - force enable!
        if (percent < CRITICAL_LEVEL && isPluggedIn && !isCharging) {
            Log.w(TAG, "WATCHDOG ALERT: Battery " + percent + "% but not charging! Force enabling...");
            forceEnableCharging();
        }

        // Also if battery is VERY low (<10%) and plugged in, always try to enable
        if (percent < 10 && isPluggedIn) {
            Log.w(TAG, "WATCHDOG: Battery critically low (" + percent + "%), ensuring charging enabled");
            forceEnableCharging();
        }

        return Result.success();
    }

    private void forceEnableCharging() {
        // Force enable on all known paths
        RootHelper.execRoot("echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null");
        RootHelper.execRoot("echo 1 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null");
        RootHelper.execRoot("echo 1 > /sys/class/power_supply/usb/charging_enabled 2>/dev/null");
        Log.i(TAG, "WATCHDOG: Charging force-enabled on all paths");
    }

    /**
     * Schedule the watchdog to run every 15 minutes.
     * Call this from MainActivity or Application.onCreate()
     */
    public static void schedule(Context context) {
        if (!GatewayApplication.isWorkManagerAvailable()) {
            Log.w(TAG, "WorkManager not available, battery watchdog disabled");
            return;
        }

        try {
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    BatteryWatchdog.class,
                    15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().build())
                .build();

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            );

            Log.i(TAG, "Battery watchdog scheduled (every 15 min)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule battery watchdog: " + e.getMessage());
        }
    }

    /**
     * Cancel the watchdog.
     */
    public static void cancel(Context context) {
        if (!GatewayApplication.isWorkManagerAvailable()) {
            return;
        }

        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            Log.i(TAG, "Battery watchdog cancelled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to cancel battery watchdog: " + e.getMessage());
        }
    }
}
