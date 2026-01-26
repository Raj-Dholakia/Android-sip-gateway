package org.onetwoone.gateway;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Handles SMS operations for the GSM-SIP Gateway.
 *
 * Incoming SMS (GSM -> SIP):
 * - Monitors content://sms/inbox via ContentObserver
 * - Sends SMS content as SIP MESSAGE to Asterisk
 * - Deletes SMS from inbox on successful delivery
 *
 * Outgoing SMS (SIP -> GSM):
 * - Receives destination and body from SIP MESSAGE
 * - Sends via SmsManager
 * - Reports delivery status back
 */
public class SmsHandler {
    private static final String TAG = "SmsHandler";

    private static final Uri SMS_INBOX_URI = Uri.parse("content://sms/inbox");
    private static final Uri SMS_URI = Uri.parse("content://sms");

    private static final String ACTION_SMS_SENT = "org.onetwoone.gateway.SMS_SENT";
    private static final String ACTION_SMS_DELIVERED = "org.onetwoone.gateway.SMS_DELIVERED";

    private final Context context;
    private final SmsCallback callback;
    private final Handler mainHandler;

    private ContentObserver smsObserver;
    private BroadcastReceiver smsSentReceiver;
    private BroadcastReceiver smsDeliveredReceiver;

    // Track processed SMS IDs to avoid duplicates
    private final Set<Long> processedSmsIds = new HashSet<>();

    private boolean isRunning = false;

    public interface SmsCallback {
        /**
         * Called when an incoming SMS needs to be sent to SIP.
         * @param from Sender phone number
         * @param body SMS text
         * @param smsId SMS ID in the database (for deletion after successful send)
         * @param simSlot SIM slot (1 or 2) that received the SMS
         */
        void onIncomingSms(String from, String body, long smsId, int simSlot);

        /**
         * Called when outgoing SMS status changes.
         * @param destination Phone number
         * @param status "sent", "delivered", or "failed"
         * @param errorMessage Error details if failed
         */
        void onSmsSendStatus(String destination, String status, String errorMessage);
    }

    public SmsHandler(Context context, SmsCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start monitoring SMS inbox for new messages.
     */
    public void start() {
        if (isRunning) {
            Log.w(TAG, "SmsHandler already running");
            return;
        }

        Log.d(TAG, "Starting SMS handler");

        // Register ContentObserver for inbox changes
        smsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                Log.d(TAG, "SMS inbox changed");
                // Process immediately - no debounce, race with MessagingApp
                processInbox();
            }
        };

        context.getContentResolver().registerContentObserver(
            SMS_URI, true, smsObserver
        );

        // Register broadcast receivers for send status
        registerSendReceivers();

        // Process any existing unprocessed SMS
        processInbox();

        isRunning = true;
        Log.d(TAG, "SMS handler started");
    }

    /**
     * Stop monitoring SMS inbox.
     */
    public void stop() {
        if (!isRunning) {
            return;
        }

        Log.d(TAG, "Stopping SMS handler");

        if (smsObserver != null) {
            context.getContentResolver().unregisterContentObserver(smsObserver);
            smsObserver = null;
        }

        unregisterSendReceivers();

        isRunning = false;
        Log.d(TAG, "SMS handler stopped");
    }

    // Counter for tracing
    private static int processInboxCounter = 0;

    /**
     * Process all unread SMS in inbox.
     * Public so it can be called when SIP registration is restored.
     */
    public void processInbox() {
        int traceId = ++processInboxCounter;
        Log.d(TAG, "[" + traceId + "] processInbox START, processedIds=" + processedSmsIds);

        ContentResolver resolver = context.getContentResolver();

        // Query unread SMS
        String[] projection = {"_id", "address", "body", "date", "read", "sub_id"};
        String selection = "read = 0"; // Only unread

        try (Cursor cursor = resolver.query(
                SMS_INBOX_URI, projection, selection, null, "date ASC")) {

            if (cursor == null) {
                Log.w(TAG, "[" + traceId + "] SMS cursor is null");
                return;
            }

            Log.d(TAG, "[" + traceId + "] Found " + cursor.getCount() + " unread SMS");

            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                int subId = cursor.getInt(cursor.getColumnIndexOrThrow("sub_id"));

                // Convert subscription ID to SIM slot (1 or 2)
                int simSlot = getSimSlotFromSubId(subId);

                // Skip if already processed (in case of duplicate notifications)
                if (processedSmsIds.contains(id)) {
                    Log.d(TAG, "[" + traceId + "] SKIP id=" + id + " (already processed)");
                    continue;
                }

                Log.d(TAG, "[" + traceId + "] Processing SMS id=" + id + " from=" + address + " body=\"" + body + "\" SIM" + simSlot);

                // Mark as being processed BEFORE callback
                processedSmsIds.add(id);
                Log.d(TAG, "[" + traceId + "] Added id=" + id + " to processedIds, now=" + processedSmsIds);

                // Notify callback
                if (callback != null) {
                    Log.d(TAG, "[" + traceId + "] Calling callback for id=" + id);
                    callback.onIncomingSms(address, body, id, simSlot);
                    Log.d(TAG, "[" + traceId + "] Callback returned for id=" + id);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[" + traceId + "] Error processing inbox: " + e.getMessage(), e);
        }

        Log.d(TAG, "[" + traceId + "] processInbox END, processedIds=" + processedSmsIds);
    }

    /**
     * Convert subscription ID to SIM slot number (1 or 2).
     */
    private int getSimSlotFromSubId(int subId) {
        try {
            SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subManager != null) {
                SubscriptionInfo info = subManager.getActiveSubscriptionInfo(subId);
                if (info != null) {
                    return info.getSimSlotIndex() + 1; // 0-based to 1-based
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting SIM slot: " + e.getMessage());
        }
        return 1; // Default to SIM1
    }

    /**
     * Mark SMS as processed and delete it from inbox.
     * Call this after successfully sending to SIP.
     *
     * @param smsId SMS ID to delete
     * @return true if deleted successfully
     */
    public boolean deleteSms(long smsId) {
        try {
            Uri smsUri = Uri.parse("content://sms/" + smsId);
            int deleted = context.getContentResolver().delete(smsUri, null, null);

            if (deleted > 0) {
                Log.d(TAG, "Deleted SMS id=" + smsId);
                processedSmsIds.remove(smsId);
                return true;
            } else {
                Log.w(TAG, "Failed to delete SMS id=" + smsId);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting SMS: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Mark SMS as read (alternative to deletion).
     * Falls back to root access if normal update fails.
     *
     * @param smsId SMS ID to mark as read
     */
    public void markAsRead(long smsId) {
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("read", 1);

            Uri smsUri = Uri.parse("content://sms/" + smsId);
            int updated = context.getContentResolver().update(smsUri, values, null, null);

            if (updated > 0) {
                Log.d(TAG, "Marked SMS id=" + smsId + " as read");
            } else {
                // Normal update failed (not default SMS app), try with root
                Log.d(TAG, "Normal update failed for SMS id=" + smsId + ", trying root");
                markAsReadWithRoot(smsId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error marking SMS as read: " + e.getMessage(), e);
            // Try root as fallback
            markAsReadWithRoot(smsId);
        }
    }

    /**
     * Remove SMS from processed list so it can be retried.
     * Call this when SIP MESSAGE send fails.
     *
     * @param smsId SMS ID to unprocess
     */
    public void unprocessSms(long smsId) {
        if (processedSmsIds.remove(smsId)) {
            Log.w(TAG, "UNPROCESSED SMS id=" + smsId + " for retry - this will allow re-send! processedIds=" + processedSmsIds);
            // Log stack trace to see who called this
            Log.w(TAG, "unprocessSms called from:", new Exception("Stack trace"));
        }
    }

    /**
     * Mark SMS as read using root access via direct sqlite3.
     */
    private void markAsReadWithRoot(long smsId) {
        // The SMS database can be in different locations depending on Android version
        String[] dbPaths = {
            "/data/user_de/0/com.android.providers.telephony/databases/mmssms.db",
            "/data/data/com.android.providers.telephony/databases/mmssms.db"
        };

        for (String dbPath : dbPaths) {
            String cmd = "sqlite3 " + dbPath + " \"UPDATE sms SET read=1 WHERE _id=" + smsId + "\"";
            String result = RootHelper.execRoot(cmd);
            if (result != null) {
                Log.d(TAG, "Marked SMS id=" + smsId + " as read (root sqlite3)");
                return;
            }
        }
        Log.w(TAG, "Failed to mark SMS id=" + smsId + " as read with root");
    }

    /**
     * Send an SMS message using default SIM.
     *
     * @param destination Phone number to send to
     * @param message Message text
     */
    public void sendSms(String destination, String message) {
        sendSms(destination, message, 0); // 0 = default SIM
    }

    /**
     * Send an SMS message via specific SIM slot.
     *
     * @param destination Phone number to send to
     * @param message Message text
     * @param simSlot SIM slot (1 or 2), or 0 for default
     */
    public void sendSms(String destination, String message, int simSlot) {
        Log.d(TAG, "Sending SMS to " + destination + " via SIM" + (simSlot == 0 ? "default" : String.valueOf(simSlot)) + ": " + message);

        try {
            SmsManager smsManager = getSmsManagerForSlot(simSlot);

            // Create pending intents for status
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            Intent sentIntent = new Intent(ACTION_SMS_SENT);
            sentIntent.putExtra("destination", destination);
            PendingIntent sentPI = PendingIntent.getBroadcast(
                context, destination.hashCode(), sentIntent, flags);

            Intent deliveredIntent = new Intent(ACTION_SMS_DELIVERED);
            deliveredIntent.putExtra("destination", destination);
            PendingIntent deliveredPI = PendingIntent.getBroadcast(
                context, destination.hashCode() + 1, deliveredIntent, flags);

            // Check if message needs to be split
            ArrayList<String> parts = smsManager.divideMessage(message);

            if (parts.size() == 1) {
                smsManager.sendTextMessage(
                    destination, null, message, sentPI, deliveredPI);
            } else {
                ArrayList<PendingIntent> sentPIs = new ArrayList<>();
                ArrayList<PendingIntent> deliveredPIs = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    sentPIs.add(sentPI);
                    deliveredPIs.add(deliveredPI);
                }
                smsManager.sendMultipartTextMessage(
                    destination, null, parts, sentPIs, deliveredPIs);
            }

            Log.d(TAG, "SMS queued for sending (" + parts.size() + " parts)");

        } catch (Exception e) {
            Log.e(TAG, "Error sending SMS: " + e.getMessage(), e);
            if (callback != null) {
                callback.onSmsSendStatus(destination, "failed", e.getMessage());
            }
        }
    }

    /**
     * Get SmsManager for specific SIM slot.
     * @param simSlot 1 or 2 for specific SIM, 0 for default
     */
    private SmsManager getSmsManagerForSlot(int simSlot) {
        if (simSlot <= 0) {
            // Use default
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return context.getSystemService(SmsManager.class);
            } else {
                return SmsManager.getDefault();
            }
        }

        // Get subscription ID for the slot
        try {
            SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subManager != null) {
                java.util.List<SubscriptionInfo> subList = subManager.getActiveSubscriptionInfoList();
                if (subList != null) {
                    for (SubscriptionInfo info : subList) {
                        if (info.getSimSlotIndex() + 1 == simSlot) {
                            int subId = info.getSubscriptionId();
                            Log.d(TAG, "Using SIM" + simSlot + " (subId=" + subId + ")");
                            return SmsManager.getSmsManagerForSubscriptionId(subId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting SmsManager for slot " + simSlot + ": " + e.getMessage());
        }

        // Fallback to default
        Log.w(TAG, "SIM" + simSlot + " not found, using default");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(SmsManager.class);
        } else {
            return SmsManager.getDefault();
        }
    }

    private void registerSendReceivers() {
        // Sent status receiver
        smsSentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String destination = intent.getStringExtra("destination");
                String status;
                String error = null;

                switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        status = "sent";
                        Log.d(TAG, "SMS sent successfully to " + destination);
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                        status = "failed";
                        error = "Generic failure";
                        break;
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                        status = "failed";
                        error = "No service";
                        break;
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                        status = "failed";
                        error = "Null PDU";
                        break;
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        status = "failed";
                        error = "Radio off";
                        break;
                    default:
                        status = "failed";
                        error = "Unknown error: " + getResultCode();
                }

                if (callback != null) {
                    callback.onSmsSendStatus(destination, status, error);
                }
            }
        };

        // Delivered status receiver
        smsDeliveredReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String destination = intent.getStringExtra("destination");

                if (getResultCode() == Activity.RESULT_OK) {
                    Log.d(TAG, "SMS delivered to " + destination);
                    if (callback != null) {
                        callback.onSmsSendStatus(destination, "delivered", null);
                    }
                } else {
                    Log.w(TAG, "SMS delivery failed to " + destination);
                    if (callback != null) {
                        callback.onSmsSendStatus(destination, "delivery_failed",
                            "Delivery failed: " + getResultCode());
                    }
                }
            }
        };

        // Register receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(smsSentReceiver,
                new IntentFilter(ACTION_SMS_SENT), Context.RECEIVER_NOT_EXPORTED);
            context.registerReceiver(smsDeliveredReceiver,
                new IntentFilter(ACTION_SMS_DELIVERED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(smsSentReceiver, new IntentFilter(ACTION_SMS_SENT));
            context.registerReceiver(smsDeliveredReceiver, new IntentFilter(ACTION_SMS_DELIVERED));
        }
    }

    private void unregisterSendReceivers() {
        try {
            if (smsSentReceiver != null) {
                context.unregisterReceiver(smsSentReceiver);
                smsSentReceiver = null;
            }
            if (smsDeliveredReceiver != null) {
                context.unregisterReceiver(smsDeliveredReceiver);
                smsDeliveredReceiver = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering receivers: " + e.getMessage());
        }
    }
}
