#!/bin/bash
# Script to update SSL certificates from Caddy host
#
# This script copies Let's Encrypt certificates from a Caddy server
# and installs them for Asterisk PJSIP TLS transport.
#
# Setup:
# 1. Configure passwordless SSH from Asterisk server to Caddy host
# 2. Update CERT_HOST with your Caddy server IP/hostname
# 3. Update CERT_PATH with actual certificate path on Caddy server
# 4. Update DEST_PATH if needed (default: /etc/asterisk/keys)
# 5. Add to crontab: 0 3 * * * /usr/local/bin/update-certs.sh

CERT_HOST="YOUR_CADDY_HOST_IP"
CERT_PATH="/var/lib/caddy/.local/share/caddy/certificates/acme-v02.api.letsencrypt.org-directory/YOUR_DOMAIN"
DEST_PATH="/etc/asterisk/keys"

# Copy certificates
scp -o StrictHostKeyChecking=no -o LogLevel=ERROR \
    root@${CERT_HOST}:${CERT_PATH}/YOUR_DOMAIN.crt ${DEST_PATH}/ 2>/dev/null

scp -o StrictHostKeyChecking=no -o LogLevel=ERROR \
    root@${CERT_HOST}:${CERT_PATH}/YOUR_DOMAIN.key ${DEST_PATH}/ 2>/dev/null

# Set permissions
chown asterisk:asterisk ${DEST_PATH}/*.* 2>/dev/null
chmod 600 ${DEST_PATH}/*.key 2>/dev/null
chmod 644 ${DEST_PATH}/*.crt 2>/dev/null

# Reload Asterisk PJSIP if running
if systemctl is-active --quiet asterisk; then
    asterisk -rx "module reload res_pjsip.so" >/dev/null 2>&1
fi

exit 0
