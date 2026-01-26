package org.onetwoone.gateway;

import android.util.Log;

import org.pjsip.pjsua2.*;

/**
 * PJSIP Account implementation for GSM-SIP Gateway.
 * Base class for SIP account - callbacks should be overridden by subclasses.
 *
 * Note: This is typically used via SipAccountManager.AccountCallbackWrapper
 * which overrides all callback methods.
 */
public class GatewayAccount extends Account {
    private static final String TAG = "GatewayAccount";

    /**
     * Default constructor for subclasses that override callbacks.
     */
    public GatewayAccount() {
        // Subclasses should override callback methods
    }

    @Override
    public void onRegState(OnRegStateParam prm) {
        try {
            AccountInfo info = getInfo();
            boolean registered = (info.getRegStatus() == pjsip_status_code.PJSIP_SC_OK);
            String reason = info.getRegStatusText();
            Log.d(TAG, "Registration state: " + info.getRegStatus() + " - " + reason);
            // Subclasses should override to handle
        } catch (Exception e) {
            Log.e(TAG, "Error in onRegState: " + e.getMessage());
        }
    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {
        Log.d(TAG, "Incoming call, callId=" + prm.getCallId());
        // Subclasses should override to handle
    }

    @Override
    public void onInstantMessage(OnInstantMessageParam prm) {
        try {
            String from = prm.getFromUri();
            String body = prm.getMsgBody();
            Log.d(TAG, "Instant message from " + from + ": " + body);
            // Subclasses should override to handle
        } catch (Exception e) {
            Log.e(TAG, "Error handling instant message: " + e.getMessage());
        }
    }
}
