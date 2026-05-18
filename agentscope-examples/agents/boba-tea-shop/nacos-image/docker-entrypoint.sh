#!/bin/bash
# Custom entrypoint for Nacos with password initialization
# This script starts the password init script in background and then starts Nacos

echo "[entrypoint] Starting Nacos with password initialization..."

# Start the password initialization script in background
/home/nacos/init-password.sh &

# Execute the original Nacos startup
# The base Nacos image uses bin/docker-startup.sh
exec /home/nacos/bin/docker-startup.sh

