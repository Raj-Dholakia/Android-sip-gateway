#!/usr/bin/env python3
"""
Gateway events notification script for Asterisk.
Logs all calls and SMS to SQLite and sends notifications to Telegram.

Usage:
  gateway-notify.py <gateway_id> <event_type> <from> <to> [message_text]

Arguments:
  gateway_id   - 'gateway1' or 'gateway2'
  event_type   - 'incoming_call', 'outgoing_call', 'incoming_sms', 'outgoing_sms'
  from         - Caller/sender number
  to           - Called/recipient number
  message_text - SMS text (only for SMS events)

Example:
  gateway-notify.py gateway1 incoming_call +79817533039 101
  gateway-notify.py gateway2 outgoing_sms 102 +79817533039 "Hello world"
"""

import sys
import sqlite3
import telebot
from datetime import datetime

# Configuration
TG_API_KEY = 'YOUR_TELEGRAM_BOT_API_KEY'
TG_USERS = {
    'gateway1': 'YOUR_TELEGRAM_USER_ID_1',
    'gateway2': 'YOUR_TELEGRAM_USER_ID_2'
}
DB_PATH = '/var/lib/asterisk/gateway_events.db'

# Initialize Telegram bot
bot = telebot.TeleBot(token=TG_API_KEY)

def init_db():
    """Initialize SQLite database with events table."""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    cursor.execute('''
        CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
            gateway_id TEXT NOT NULL,
            event_type TEXT NOT NULL,
            from_number TEXT,
            to_number TEXT,
            message_text TEXT,
            sip_delivered BOOLEAN DEFAULT 0,
            tg_delivered BOOLEAN DEFAULT 0,
            retry_count INTEGER DEFAULT 0,
            last_retry DATETIME,
            delivery_error TEXT
        )
    ''')

    # Create index for faster queries
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_timestamp ON events(timestamp)
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_gateway ON events(gateway_id)
    ''')

    conn.commit()
    conn.close()

def log_event(gateway_id, event_type, from_number, to_number, message_text=None):
    """
    Log event to database.
    Returns: event_id
    """
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    cursor.execute('''
        INSERT INTO events (gateway_id, event_type, from_number, to_number, message_text)
        VALUES (?, ?, ?, ?, ?)
    ''', (gateway_id, event_type, from_number, to_number, message_text))

    event_id = cursor.lastrowid
    conn.commit()
    conn.close()

    return event_id

def mark_tg_delivered(event_id):
    """Mark event as delivered to Telegram."""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    cursor.execute('UPDATE events SET tg_delivered = 1 WHERE id = ?', (event_id,))
    conn.commit()
    conn.close()

def mark_sip_delivered(gateway_id, event_type):
    """
    Mark the most recent event of given type as delivered to SIP.
    Used after MessageSend to update delivery status.
    """
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    # Find the most recent event matching gateway and type
    cursor.execute('''
        SELECT id FROM events
        WHERE gateway_id = ? AND event_type = ?
        ORDER BY timestamp DESC
        LIMIT 1
    ''', (gateway_id, event_type))

    result = cursor.fetchone()
    if result:
        event_id = result[0]
        cursor.execute('UPDATE events SET sip_delivered = 1 WHERE id = ?', (event_id,))
        conn.commit()
        print(f"Marked event {event_id} as SIP delivered")
    else:
        print(f"No recent event found for {gateway_id}/{event_type}")

    conn.close()

def format_message(gateway_id, event_type, from_number, to_number, message_text=None):
    """
    Format message for Telegram notification.
    Includes timestamp in human-readable format.
    """
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

    # Event type emoji
    if 'call' in event_type:
        emoji = '📞'
    else:
        emoji = '💬'

    # Direction
    if 'incoming' in event_type:
        direction = '→'
        if 'call' in event_type:
            event_name = 'Incoming Call'
        else:
            event_name = 'Incoming SMS'
    else:
        direction = '←'
        if 'call' in event_type:
            event_name = 'Outgoing Call'
        else:
            event_name = 'Outgoing SMS'

    msg = f"{emoji} <b>{event_name}</b>\n"
    msg += f"🕒 {now}\n"
    msg += f"📡 {gateway_id}\n"
    msg += f"{direction} From: <code>{from_number}</code>\n"
    msg += f"{direction} To: <code>{to_number}</code>"

    if message_text:
        msg += f"\n\n💬 Text:\n<i>{message_text}</i>"

    return msg

def send_to_telegram(message, gateway_id):
    """
    Send notification to Telegram user(s).
    Returns: True if sent successfully, False otherwise
    """
    user_id = TG_USERS.get(gateway_id)

    if not user_id:
        print(f"No Telegram user configured for {gateway_id}")
        return False

    try:
        bot.send_message(user_id, message, parse_mode='HTML')
        return True
    except Exception as e:
        print(f"Message to telegram was not sended, connection error: {e}")
        return False


def mark_sip_delivered_by_id(event_id):
    """Mark specific event as delivered to SIP by event ID."""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    cursor.execute("UPDATE events SET sip_delivered = 1 WHERE id = ?", (event_id,))
    conn.commit()
    conn.close()
    print(f"Marked event {event_id} as SIP delivered")

def main():
    # Check for special commands
    # Check for --mark-sip-delivered-by-id command
    if len(sys.argv) >= 3 and sys.argv[1] == '--mark-sip-delivered-by-id':
        init_db()
        event_id = int(sys.argv[2])
        mark_sip_delivered_by_id(event_id)
        sys.exit(0)

    if len(sys.argv) >= 4 and sys.argv[1] == '--mark-sip-delivered':
        # Usage: --mark-sip-delivered <gateway_id> <event_type>
        init_db()
        mark_sip_delivered(sys.argv[2], sys.argv[3])
        sys.exit(0)

    # Check for --no-telegram flag
    no_telegram = False
    arg_offset = 1
    if len(sys.argv) > 1 and sys.argv[1] == '--no-telegram':
        no_telegram = True
        arg_offset = 2

    if len(sys.argv) < (4 + arg_offset):
        print("Usage: gateway-notify.py [--no-telegram] <gateway_id> <event_type> <from> <to> [message_text]")
        print("   or: gateway-notify.py --mark-sip-delivered <gateway_id> <event_type>")
        sys.exit(1)

    gateway_id = sys.argv[arg_offset]
    event_type = sys.argv[arg_offset + 1]
    from_number = sys.argv[arg_offset + 2]
    to_number = sys.argv[arg_offset + 3]
    message_text = sys.argv[arg_offset + 4] if len(sys.argv) > (arg_offset + 4) else None

    # Validate gateway_id
    if gateway_id not in ['gateway1', 'gateway2']:
        print(f"Invalid gateway_id: {gateway_id}")
        sys.exit(1)

    # Validate event_type
    valid_events = ['incoming_call', 'outgoing_call', 'incoming_sms', 'outgoing_sms', 'internal_message']
    if event_type not in valid_events:
        print(f"Invalid event_type: {event_type}")
        sys.exit(1)

    # Initialize database
    init_db()

    # Log event to database
    event_id = log_event(gateway_id, event_type, from_number, to_number, message_text)
    print(f"Event logged: id={event_id}")

    # Send Telegram notification unless --no-telegram flag is set
    if not no_telegram:
        message = format_message(gateway_id, event_type, from_number, to_number, message_text)
        tg_sent = send_to_telegram(message, gateway_id)

        # Mark as delivered to Telegram if successful
        if tg_sent:
            mark_tg_delivered(event_id)
            print(f"Telegram notification sent and marked: event_id={event_id}")
        else:
            print(f"Telegram notification failed: event_id={event_id}")
    else:
        # Skip Telegram notification but mark as "delivered" (not needed for internal messages)
        mark_tg_delivered(event_id)
        print(f"Event logged without Telegram notification: event_id={event_id}")

    sys.exit(0)

if __name__ == '__main__':
    main()
