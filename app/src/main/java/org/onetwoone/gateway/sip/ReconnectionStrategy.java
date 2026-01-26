package org.onetwoone.gateway.sip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

/**
 * Implements exponential backoff reconnection strategy.
 *
 * When connection fails, waits progressively longer before retrying:
 * 5s -> 10s -> 20s -> 40s -> 60s (max)
 *
 * Success resets the delay back to initial value.
 */
public class ReconnectionStrategy {
    private static final String TAG = "Reconnect";

    private final Handler handler;
    private final Runnable reconnectAction;

    private int currentDelay;
    private boolean enabled = true;
    private boolean pending = false;

    public ReconnectionStrategy(Runnable reconnectAction) {
        this.handler = new Handler(Looper.getMainLooper());
        this.reconnectAction = reconnectAction;
        this.currentDelay = GatewayConfig.RECONNECT_INITIAL_DELAY_MS;
    }

    /**
     * Schedule a reconnection attempt with current delay.
     * Uses exponential backoff - each call increases the delay.
     */
    public void scheduleReconnect() {
        if (!enabled) {
            Log.d(TAG, "Reconnection disabled, skipping");
            return;
        }

        if (pending) {
            Log.d(TAG, "Reconnection already pending, skipping");
            return;
        }

        Log.d(TAG, "Scheduling reconnection in " + currentDelay + "ms");
        pending = true;

        handler.postDelayed(() -> {
            pending = false;
            if (enabled && reconnectAction != null) {
                Log.d(TAG, "Executing reconnection");
                reconnectAction.run();
            }
        }, currentDelay);

        // Increase delay for next attempt (exponential backoff)
        currentDelay = Math.min(
            currentDelay * GatewayConfig.RECONNECT_MULTIPLIER,
            GatewayConfig.RECONNECT_MAX_DELAY_MS
        );
    }

    /**
     * Called when connection succeeds.
     * Resets the delay back to initial value.
     */
    public void onSuccess() {
        Log.d(TAG, "Connection successful, resetting delay");
        currentDelay = GatewayConfig.RECONNECT_INITIAL_DELAY_MS;
        pending = false;
    }

    /**
     * Cancel any pending reconnection.
     */
    public void cancel() {
        Log.d(TAG, "Cancelling pending reconnection");
        handler.removeCallbacksAndMessages(null);
        pending = false;
    }

    /**
     * Enable or disable reconnection.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            cancel();
        }
    }

    /**
     * Check if reconnection is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if reconnection is pending.
     */
    public boolean isPending() {
        return pending;
    }

    /**
     * Get current delay for debugging.
     */
    public int getCurrentDelay() {
        return currentDelay;
    }

    /**
     * Reset delay to initial value without waiting for success.
     */
    public void resetDelay() {
        currentDelay = GatewayConfig.RECONNECT_INITIAL_DELAY_MS;
    }
}
