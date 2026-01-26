package org.onetwoone.gateway.sip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

/**
 * Watchdog service that periodically checks for orphaned calls.
 *
 * An orphaned call is a SIP call that exists without a corresponding GSM call,
 * or vice versa. This can happen due to timing issues or crashes.
 *
 * The watchdog runs every WATCHDOG_INTERVAL_MS and calls the check callback.
 */
public class ServiceWatchdog {
    private static final String TAG = "Watchdog";

    private final Handler handler;
    private final Runnable checkCallback;
    private final Runnable watchdogRunnable;

    private boolean running = false;

    public ServiceWatchdog(Runnable checkCallback) {
        this.handler = new Handler(Looper.getMainLooper());
        this.checkCallback = checkCallback;

        this.watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }

                try {
                    if (checkCallback != null) {
                        checkCallback.run();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Watchdog check failed: " + e.getMessage());
                }

                // Schedule next check
                if (running) {
                    handler.postDelayed(this, GatewayConfig.WATCHDOG_INTERVAL_MS);
                }
            }
        };
    }

    /**
     * Start the watchdog.
     */
    public void start() {
        if (running) {
            Log.d(TAG, "Watchdog already running");
            return;
        }

        running = true;
        handler.postDelayed(watchdogRunnable, GatewayConfig.WATCHDOG_INTERVAL_MS);
        Log.d(TAG, "Watchdog started (interval: " + GatewayConfig.WATCHDOG_INTERVAL_MS + "ms)");
    }

    /**
     * Stop the watchdog.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        handler.removeCallbacks(watchdogRunnable);
        Log.d(TAG, "Watchdog stopped");
    }

    /**
     * Check if watchdog is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Trigger an immediate check (in addition to scheduled checks).
     */
    public void checkNow() {
        if (checkCallback != null) {
            handler.post(checkCallback);
        }
    }
}
