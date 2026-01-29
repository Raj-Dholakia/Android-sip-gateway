#!/usr/bin/env python3
"""
Retry failed SIP MESSAGE deliveries using Asterisk call files.

This script:
1. Queries database for SMS with sip_delivered=0
2. Creates call files to trigger message resend
3. Updates retry count and status
4. Retries indefinitely until successful delivery (no max attempts limit)

Should be run periodically via cron (e.g., every minute)
"""

import sys
import sqlite3
import os
import time
from datetime import datetime

DB_PATH = '/var/lib/asterisk/gateway_events.db'
CALL_FILE_DIR = '/var/spool/asterisk/outgoing'
MAX_RETRIES = 999999  # Effectively unlimited - retry forever until delivered
RETRY_DELAY_MINUTES = 0.17  # Wait 1 minute between retry attempts

def get_failed_messages():
    """Get all undelivered SMS that are ready for retry."""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    cursor.execute('''
        SELECT id, gateway_id, event_type, from_number, to_number, message_text, retry_count
        FROM events
        WHERE event_type IN ('incoming_sms', 'outgoing_sms', 'internal_message')
          AND sip_delivered = 0
          AND retry_count < ?
          AND (last_retry IS NULL OR last_retry < datetime('now', '-' || ? || ' minutes'))
        ORDER BY timestamp ASC
        LIMIT 10
    ''', (MAX_RETRIES, RETRY_DELAY_MINUTES))

    results = cursor.fetchall()
    conn.close()
    return results

def create_call_file(event_id, gateway_id, event_type, from_number, to_number, message_text):
    """
    Create Asterisk call file to retry message delivery.
    Returns: (success: bool, error: str or None)
    """
    try:
        # Escape message text for dialplan
        body_escaped = (message_text or "").replace('"', '\\"').replace('\n', ' ')

        if event_type == 'incoming_sms':
            # GSM → SIP client
            target = to_number  # 101-104
            context = 'retry-sms'
            extension = f'incoming-{target}'
            variables = f'FROM={from_number},TO={target},BODY="{body_escaped}",GATEWAY={gateway_id},EVENT_ID={event_id}'

        elif event_type == 'internal_message':
            # SIP client → SIP client (internal)
            target = to_number  # 101-104
            context = 'retry-sms'
            extension = f'internal-{target}'
            variables = f'FROM={from_number},TO={target},BODY="{body_escaped}",GATEWAY={gateway_id},EVENT_ID={event_id}'

        elif event_type == 'outgoing_sms':
            # SIP → GSM gateway
            target = to_number  # Phone number
            gateway = gateway_id
            context = 'retry-sms'
            extension = f'outgoing-{gateway}'
            variables = f'FROM={from_number},TO={target},BODY="{body_escaped}",GATEWAY={gateway},EVENT_ID={event_id}'
        else:
            return False, f"Invalid event type: {event_type}"

        # Create call file content
        # For Local channels, Asterisk requires either Application or Context+Extension
        # Using Application: Noop allows the Local channel to execute its dialplan naturally
        call_file_content = f"""Channel: Local/{extension}@{context}/n
Application: Noop
MaxRetries: 0
RetryTime: 60
WaitTime: 30
Setvar: FROM={from_number}
Setvar: TO={to_number}
Setvar: BODY={body_escaped}
Setvar: GATEWAY={gateway_id}
Setvar: EVENT_ID={event_id}
"""

        # Write to temporary file first, then move (atomic operation)
        temp_file = f"/tmp/retry-{event_id}-{int(time.time())}.call"
        final_file = f"{CALL_FILE_DIR}/retry-{event_id}-{int(time.time())}.call"

        with open(temp_file, 'w') as f:
            f.write(call_file_content)

        # Set permissions (asterisk user must be able to read/delete)
        os.chmod(temp_file, 0o666)

        # Move to outgoing directory (Asterisk will pick it up)
        os.rename(temp_file, final_file)

        print(f"  Created call file: {final_file}")
        return True, None

    except Exception as e:
        return False, str(e)

def update_retry_attempt(event_id):
    """Increment retry count for an event."""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    cursor.execute('''
        UPDATE events
        SET retry_count = retry_count + 1,
            last_retry = datetime('now')
        WHERE id = ?
    ''', (event_id,))

    conn.commit()
    conn.close()

def main():
    print(f"[{datetime.now()}] Starting message retry check...")

    # Check if outgoing directory exists
    if not os.path.exists(CALL_FILE_DIR):
        print(f"ERROR: Asterisk outgoing directory not found: {CALL_FILE_DIR}")
        return 1

    messages = get_failed_messages()

    if not messages:
        print("No failed messages to retry")
        return 0

    print(f"Found {len(messages)} messages to retry")

    queued_count = 0
    fail_count = 0

    for msg in messages:
        event_id, gateway_id, event_type, from_num, to_num, body, retry_count = msg

        print(f"Retrying event {event_id}: {event_type} from {from_num} to {to_num} (attempt {retry_count + 1})")

        success, error = create_call_file(event_id, gateway_id, event_type, from_num, to_num, body)

        if success:
            queued_count += 1
            update_retry_attempt(event_id)
        else:
            print(f"  ✗ Failed to create call file: {error}")
            fail_count += 1

    print(f"Queued {queued_count} messages for retry, {fail_count} failed to queue")

    return 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)
