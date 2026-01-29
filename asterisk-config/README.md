# Asterisk Configuration for GSM-SIP Gateway

Complete Asterisk configuration for the Android GSM-SIP Gateway project with **guaranteed message delivery**.

## Overview

This configuration sets up an Asterisk 20 server with:
- **PJSIP** with TLS transport and mandatory SRTP encryption
- **4 SIP clients** (101-104) for internal communication
- **2 GSM gateways** (gateway1, gateway2) connecting Android devices
- **Call routing**: 101/102 via gateway1, 103/104 via gateway2
- **SMS support** via SIP MESSAGE with guaranteed delivery
- **Event logging** to SQLite database
- **Automatic retry** mechanism for failed message deliveries
- **Telegram notifications** for all calls and SMS

## Architecture

```
SIP Clients (101-104)  <--TLS/SRTP-->  Asterisk  <--TLS/SRTP-->  Android Gateways (gateway1/2)  <-->  GSM Network
```

### Key Features

**Guaranteed Message Delivery:**
- Messages are logged to database before attempting delivery
- PJSIP qualify mechanism checks endpoint availability every 30 seconds
- Only delivers messages to endpoints with `Avail` status
- Failed deliveries are automatically retried every 10 seconds
- Unlimited retry attempts until successful delivery
- Tracks delivery status per message with `sip_delivered` flag

**Secure Communications:**
- Mandatory TLS for SIP signaling (port 5061)
- Mandatory SRTP for media encryption
- Let's Encrypt certificate auto-renewal

### Components

- **Asterisk 20 LTS**: PJSIP stack with TLS and SRTP
- **Let's Encrypt certificates**: Auto-renewal from Caddy server
- **Python notification script**: Logs events and sends Telegram notifications
- **Python retry script**: Automatically retries failed message deliveries
- **SQLite database**: Stores all call/SMS events with delivery tracking

## Installation

### 1. System Requirements

- Ubuntu 24.04 LTS (or compatible)
- Asterisk 20 (available in Ubuntu repos)
- Python 3 with pip
- Valid TLS certificates (Let's Encrypt recommended)

### 2. Install Asterisk

```bash
apt update
apt install -y asterisk python3-pip sqlite3
pip3 install pyTelegramBotAPI --break-system-packages
```

### 3. Configure Asterisk

Copy configuration files:

```bash
# Copy main configs
cp etc/asterisk/pjsip.conf /etc/asterisk/
cp etc/asterisk/extensions.conf /etc/asterisk/
cp etc/asterisk/rtp.conf /etc/asterisk/

# Copy scripts
cp scripts/gateway-notify.py /usr/local/bin/
cp scripts/retry-failed-messages.py /usr/local/bin/
cp scripts/update-certs.sh /usr/local/bin/
chmod +x /usr/local/bin/gateway-notify.py
chmod +x /usr/local/bin/retry-failed-messages.py
chmod +x /usr/local/bin/update-certs.sh

# Create database directory with correct permissions
mkdir -p /var/lib/asterisk
chown asterisk:asterisk /var/lib/asterisk
```

### 4. Update Configuration

Edit `/etc/asterisk/pjsip.conf`:
- Replace `YOUR_DOMAIN` with your actual domain (e.g., `sip.example.com`)
- Replace `YOUR_PASSWORD_HERE` with strong, unique passwords for each endpoint

Edit `/usr/local/bin/gateway-notify.py`:
- Replace `YOUR_TELEGRAM_BOT_API_KEY` with your bot token from @BotFather
- Replace `YOUR_TELEGRAM_USER_ID_1` and `YOUR_TELEGRAM_USER_ID_2` with user IDs

Edit `/usr/local/bin/update-certs.sh`:
- Replace `YOUR_CADDY_HOST_IP` with Caddy server IP
- Replace `YOUR_DOMAIN` with your actual domain

### 5. Setup TLS Certificates

Place your TLS certificates:

```bash
mkdir -p /etc/asterisk/keys
# Copy certificates to /etc/asterisk/keys/YOUR_DOMAIN.crt and YOUR_DOMAIN.key
chown asterisk:asterisk /etc/asterisk/keys/*
chmod 600 /etc/asterisk/keys/*.key
chmod 644 /etc/asterisk/keys/*.crt
```

### 6. Setup Cron Jobs

Add to root crontab (`crontab -e`):

```cron
# Update TLS certificates daily at 3 AM
0 3 * * * /usr/local/bin/update-certs.sh

# Retry failed message deliveries every 10 seconds
* * * * * /usr/local/bin/retry-failed-messages.py >> /var/log/asterisk/message-retry.log 2>&1
* * * * * sleep 10; /usr/local/bin/retry-failed-messages.py >> /var/log/asterisk/message-retry.log 2>&1
* * * * * sleep 20; /usr/local/bin/retry-failed-messages.py >> /var/log/asterisk/message-retry.log 2>&1
* * * * * sleep 30; /usr/local/bin/retry-failed-messages.py >> /var/log/asterisk/message-retry.log 2>&1
* * * * * sleep 40; /usr/local/bin/retry-failed-messages.py >> /var/log/asterisk/message-retry.log 2>&1
* * * * * sleep 50; /usr/local/bin/retry-failed-messages.py >> /var/log/asterisk/message-retry.log 2>&1

# Optional: Cleanup old events (older than 90 days) weekly
# 0 4 * * 0 sqlite3 /var/lib/asterisk/gateway_events.db "DELETE FROM events WHERE timestamp < datetime('now', '-90 days')"

# Optional: Database vacuum for optimization
# 30 4 * * * sqlite3 /var/lib/asterisk/gateway_events.db "VACUUM"
```

### 7. Firewall Configuration

Open required ports:

```bash
# SIP TLS
ufw allow 5061/tcp

# RTP (adjust range as needed)
ufw allow 10000:10100/udp
```

### 8. Start Asterisk

```bash
systemctl enable asterisk
systemctl restart asterisk
```

### 9. Verify Configuration

```bash
# Check PJSIP endpoints
asterisk -rx "pjsip show endpoints"

# Check contact status (should show "Avail" for online endpoints)
asterisk -rx "pjsip show contacts"

# Check if transport is listening
asterisk -rx "pjsip show transports"

# Test notification script
/usr/local/bin/gateway-notify.py gateway1 incoming_call +79123456789 101

# Test retry mechanism
/usr/local/bin/retry-failed-messages.py
```

## Configuration Files

### pjsip.conf

Main PJSIP configuration defining:
- TLS transport on port 5061
- 4 SIP client endpoints (101-104)
- 2 GSM gateway endpoints (gateway1, gateway2)
- **Qualify mechanism**: Checks endpoint availability every 30 seconds
- Mandatory SRTP encryption
- Authentication credentials

**Key settings for guaranteed delivery:**
```ini
[101]
type=aor
qualify_frequency=30    # Ping endpoint every 30 seconds
max_contacts=3
remove_existing=yes
```

### extensions.conf

Dialplan defining:
- Internal calls between clients
- Outbound calls routing (SIP → GSM via gateways)
- Inbound calls routing (GSM → SIP clients)
- **SMS routing with delivery checking**:
  - Checks endpoint availability using `PJSIP_CONTACT(endpoint, status)`
  - Only sends messages to endpoints with `Avail` status
  - Logs all messages to database before sending
  - Marks as delivered only after successful `MessageSend()`
- **Retry context** for failed message deliveries
- Event logging via gateway-notify.py

**Message delivery flow:**
1. Message received → Log to database with `sip_delivered=0`
2. Check `PJSIP_CONTACT(endpoint, status)`
3. If status = `Avail` → Send via `MessageSend()`
4. If `MESSAGE_SEND_STATUS` = `SUCCESS` → Mark `sip_delivered=1`
5. Otherwise leave `sip_delivered=0` for retry

### rtp.conf

RTP configuration:
- RTP port range: 10000-10100
- Adjust based on concurrent call capacity

## Scripts

### gateway-notify.py

Python script that:
1. Logs all events to SQLite database
2. Sends Telegram notifications for calls and SMS
3. Tracks delivery status (sip_delivered, tg_delivered, retry_count)
4. Marks specific events as delivered by ID

**Usage:**
```bash
# Log event
gateway-notify.py <gateway_id> <event_type> <from> <to> [message_text]

# Mark last event as SIP delivered (deprecated)
gateway-notify.py --mark-sip-delivered <gateway_id> <event_type>

# Mark specific event as SIP delivered by ID
gateway-notify.py --mark-sip-delivered-by-id <event_id>

# Log without Telegram notification (for internal messages)
gateway-notify.py --no-telegram <gateway_id> internal_message <from> <to> <text>
```

### retry-failed-messages.py

Python script that:
1. Queries database for messages with `sip_delivered=0`
2. Creates Asterisk call files to trigger retry in dialplan
3. Updates retry_count and last_retry timestamp
4. Retries every 10 seconds (via cron) until delivery succeeds
5. Effectively unlimited retry attempts (max 999999)
6. Implements 10-second delay between retry attempts per message

**How it works:**
- Runs every 10 seconds via cron
- Finds messages where:
  - `sip_delivered = 0`
  - `retry_count < 999999`
  - `last_retry` is NULL OR older than 10 seconds
- Creates call file in `/var/spool/asterisk/outgoing/`
- Asterisk picks up call file and executes retry-sms context
- Increments retry_count and updates last_retry timestamp

### update-certs.sh

Bash script that:
1. Copies certificates from Caddy server via SCP
2. Sets correct permissions
3. Reloads Asterisk PJSIP module

## Database Schema

**events table** (`/var/lib/asterisk/gateway_events.db`):

```sql
CREATE TABLE events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    gateway_id TEXT NOT NULL,           -- 'gateway1' or 'gateway2'
    event_type TEXT NOT NULL,           -- 'incoming_call', 'outgoing_call',
                                        -- 'incoming_sms', 'outgoing_sms', 'internal_message'
    from_number TEXT,
    to_number TEXT,
    message_text TEXT,                  -- NULL for calls
    sip_delivered BOOLEAN DEFAULT 0,    -- Set to 1 after successful MessageSend
    tg_delivered BOOLEAN DEFAULT 0,     -- Set to 1 after successful Telegram notification
    retry_count INTEGER DEFAULT 0,      -- Number of retry attempts
    last_retry DATETIME                 -- Timestamp of last retry attempt
);
```

**Important notes:**
- Database file must be owned by `asterisk` user: `chown asterisk:asterisk /var/lib/asterisk/gateway_events.db`
- Without correct permissions, logging will fail silently

Query examples:

```bash
# Show recent events
sqlite3 /var/lib/asterisk/gateway_events.db "SELECT * FROM events ORDER BY timestamp DESC LIMIT 10"

# Count events by type
sqlite3 /var/lib/asterisk/gateway_events.db "SELECT event_type, COUNT(*) FROM events GROUP BY event_type"

# Find pending deliveries (waiting for retry)
sqlite3 /var/lib/asterisk/gateway_events.db "SELECT * FROM events WHERE sip_delivered = 0 AND event_type LIKE '%sms'"

# Check retry statistics
sqlite3 /var/lib/asterisk/gateway_events.db "SELECT AVG(retry_count), MAX(retry_count) FROM events WHERE sip_delivered = 1"
```

## Guaranteed Message Delivery System

### How It Works

1. **Endpoint Monitoring**:
   - Asterisk sends OPTIONS ping to each endpoint every 30 seconds (`qualify_frequency=30`)
   - Endpoints that respond are marked as `Avail`
   - Offline endpoints show `Unavailable` or `(null)` status

2. **Message Sending**:
   - When message arrives, it's immediately logged to database with `sip_delivered=0`
   - Dialplan checks `PJSIP_CONTACT(endpoint, status)`
   - If status = `Avail`: sends message via `MessageSend()` and marks as delivered if successful
   - If status ≠ `Avail`: skips sending, leaves `sip_delivered=0`

3. **Automatic Retry**:
   - Cron runs retry script every 10 seconds
   - Script finds all messages with `sip_delivered=0`
   - Creates call files for each pending message
   - Asterisk executes retry context which checks status again
   - If delivery succeeds: marks `sip_delivered=1` by event ID
   - If fails: increments retry_count, updates last_retry

4. **Why This Works**:
   - **No false positives**: Only marks delivered after confirmed `MessageSend() SUCCESS` to `Avail` endpoint
   - **Fast retry**: 10-second intervals ensure quick delivery when recipient comes online
   - **Persistent**: Unlimited retries until delivery succeeds
   - **Per-message tracking**: Uses event_id to mark specific messages, prevents race conditions

### Checking Delivery Status

```bash
# Check endpoint status
asterisk -rx "pjsip show contacts"

# Check pending deliveries
sqlite3 /var/lib/asterisk/gateway_events.db \
  "SELECT id, from_number, to_number, message_text, retry_count FROM events WHERE sip_delivered=0"

# Monitor retry log
tail -f /var/log/asterisk/message-retry.log

# Test delivery to offline endpoint
# 1. Turn off endpoint 101
# 2. Send message from 104 to 101
# 3. Check database - should show sip_delivered=0
# 4. Turn on endpoint 101
# 5. Within 10 seconds, message should be delivered and marked sip_delivered=1
```

## Telegram Bot Setup

1. Create bot with @BotFather in Telegram
2. Get API token (format: `123456789:ABCdefGHIjklMNOpqrsTUVwxyz`)
3. Send `/start` to your bot
4. Get your user ID using @userinfobot
5. Update `TG_API_KEY` and `TG_USERS` in gateway-notify.py

## Troubleshooting

### Check Asterisk logs
```bash
tail -f /var/log/asterisk/messages
```

### Enable PJSIP debug
```bash
asterisk -rx "pjsip set logger on"
asterisk -rx "core set verbose 5"
asterisk -rx "core set debug 5"
```

### Check registered endpoints and their status
```bash
asterisk -rx "pjsip show endpoints"
asterisk -rx "pjsip show contacts"  # Look for "Avail" status
asterisk -rx "pjsip show aors"
```

### Test PJSIP_CONTACT function
```bash
asterisk -rx "dialplan set exten test@test-context"
asterisk -rx "dialplan add extension test,1,NoOp,test@test-context"
asterisk -rx "dialplan add extension test,2,Set,STATUS=\${PJSIP_CONTACT(101,status)},test@test-context"
asterisk -rx "dialplan add extension test,3,NoOp,Status: \${STATUS},test@test-context"
asterisk -rx "channel originate Local/test@test-context extension test@test-context"
```

### Check retry mechanism
```bash
# Manual run
/usr/local/bin/retry-failed-messages.py

# Check retry log
tail -f /var/log/asterisk/message-retry.log

# Check pending messages
sqlite3 /var/lib/asterisk/gateway_events.db "SELECT * FROM events WHERE sip_delivered=0"
```

### Test database permissions
```bash
# Check ownership
ls -la /var/lib/asterisk/gateway_events.db
# Should show: -rw-rw-r-- asterisk asterisk

# Fix if needed
chown asterisk:asterisk /var/lib/asterisk/gateway_events.db
chmod 664 /var/lib/asterisk/gateway_events.db

# Test write access
sqlite3 /var/lib/asterisk/gateway_events.db "INSERT INTO events (gateway_id, event_type, from_number, to_number) VALUES ('test', 'test', 'test', 'test')"
```

### Common Issues

**Messages not being delivered:**
- Check endpoint status: `asterisk -rx "pjsip show contacts"`
- If status is not "Avail", endpoint is offline or not responding to OPTIONS pings
- Check qualify is enabled in pjsip.conf: `qualify_frequency=30`
- Verify retry script is running: `ps aux | grep retry-failed-messages`
- Check retry log: `tail /var/log/asterisk/message-retry.log`

**Messages marked as delivered but not received:**
- This was the original problem! Fixed by checking PJSIP_CONTACT status before sending
- If still happening, verify extensions.conf has CONTACT_STATUS check before MessageSend
- Enable PJSIP logging to see actual SIP response codes

**Database errors:**
- Check permissions: `ls -la /var/lib/asterisk/gateway_events.db`
- Should be owned by asterisk:asterisk
- Fix: `chown asterisk:asterisk /var/lib/asterisk/gateway_events.db`

**Retry not working:**
- Check cron is configured correctly: `crontab -l`
- Should have 6 entries for retry script (every 10 seconds)
- Check call files are being created: `ls -la /var/spool/asterisk/outgoing/`
- Check Asterisk can process call files: `ls -la /var/spool/asterisk/outgoing/` should be empty (Asterisk processes them immediately)

**No audio in calls:**
- Verify RTP port range matches firewall rules
- Check `rtpstart` and `rtpend` in rtp.conf
- Verify SRTP keys are being negotiated: enable PJSIP debug

**Certificate errors:**
- Verify certificates are readable by asterisk user
- Check certificate validity: `openssl x509 -in /etc/asterisk/keys/YOUR_DOMAIN.crt -noout -dates`
- Reload PJSIP: `asterisk -rx "module reload res_pjsip.so"`

**Registration failures:**
- Check credentials in pjsip.conf
- Verify Android app is using correct username/password
- Check firewall allows port 5061/tcp
- Enable PJSIP logger to see authentication details

**Telegram notifications not working:**
- Verify bot token is correct
- Ensure you've sent `/start` to the bot first
- Check network connectivity: `curl https://api.telegram.org/bot<TOKEN>/getMe`
- Check gateway-notify.py has correct TG_API_KEY and TG_USERS

## Security Considerations

- **Never commit real passwords** to version control - always use placeholders
- Use strong, unique passwords for each endpoint (minimum 12 characters, mixed case, numbers, symbols)
- Keep TLS certificates up to date
- Restrict Asterisk CLI access to localhost only
- Use firewall to limit access to SIP/RTP ports to known IPs if possible
- Regularly review event logs for suspicious activity
- Consider implementing fail2ban for SIP brute-force protection
- Set proper file permissions:
  - `/etc/asterisk/pjsip.conf`: 640, owned by asterisk:asterisk
  - `/etc/asterisk/keys/*.key`: 600, owned by asterisk:asterisk
  - `/var/lib/asterisk/gateway_events.db`: 664, owned by asterisk:asterisk
- Regularly backup the events database

## Performance Considerations

- **RTP port range**: Adjust based on concurrent calls (100 ports = ~50 concurrent calls)
- **Qualify frequency**: 30 seconds is good balance between responsiveness and network overhead
- **Retry frequency**: 10 seconds provides fast delivery without overwhelming the system
- **Database cleanup**: Regular cleanup of old events prevents database bloat
- **Database vacuum**: Weekly vacuum keeps database performant

## License

This configuration is part of the Android GSM-SIP Gateway project.

## Support

For issues related to:
- **Asterisk configuration**: Check Asterisk documentation at https://docs.asterisk.org/
- **Android gateway app**: See main project repository
- **PJSIP issues**: Refer to PJSIP documentation at https://docs.pjsip.org/
- **Guaranteed delivery**: Check troubleshooting section above
