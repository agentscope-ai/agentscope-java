#!/bin/bash
# Nacos Admin Password Initialization Script
# This script waits for Nacos to be ready and then sets the admin password

set -e

echo "[init-password] Starting Nacos password initialization script..."
echo "[init-password] Waiting for Nacos server to be ready on port 8848..."

# Wait for Nacos port 8848 to be open
MAX_RETRIES=120  # 2 minutes max wait
RETRY_INTERVAL=1
RETRIES=0

while [ $RETRIES -lt $MAX_RETRIES ]; do
    # Check if port 8848 is open using nc (netcat) or bash's /dev/tcp
    if command -v nc &> /dev/null; then
        if nc -z localhost 8848 2>/dev/null; then
            echo "[init-password] Port 8848 is now open!"
            break
        fi
    else
        # Fallback to /dev/tcp if nc is not available
        if (echo > /dev/tcp/localhost/8848) 2>/dev/null; then
            echo "[init-password] Port 8848 is now open!"
            break
        fi
    fi
    
    RETRIES=$((RETRIES + 1))
    if [ $((RETRIES % 10)) -eq 0 ]; then
        echo "[init-password] Still waiting for port 8848... (attempt $RETRIES/$MAX_RETRIES)"
    fi
    sleep $RETRY_INTERVAL
done

if [ $RETRIES -eq $MAX_RETRIES ]; then
    echo "[init-password] ERROR: Timeout waiting for Nacos server to start"
    exit 1
fi

# Additional wait for Nacos to fully initialize its API
echo "[init-password] Port is open, waiting additional 10 seconds for API to be ready..."
sleep 10

# Set admin password
echo "[init-password] Setting admin password..."

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:8080/v3/auth/user/admin" \
    -H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
    -d "password=nacos" 2>&1)

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "201" ]; then
    echo "[init-password] SUCCESS: Admin password set successfully"
    echo "[init-password] Response: $BODY"
else
    echo "[init-password] WARNING: Password setting returned HTTP $HTTP_CODE"
    echo "[init-password] Response: $BODY"
    echo "[init-password] This might be expected if password was already set"
fi

echo "[init-password] Password initialization script completed"

