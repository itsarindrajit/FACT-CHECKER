#!/bin/bash
set -e

COOKIES_FILE="/home/appuser/cookies.txt"

# Restore YouTube cookies from environment variable if set.
# On Render (ephemeral containers), files are lost on every deploy.
# We persist cookies as a base64-encoded env var and restore at startup.
if [ -n "$YTDLP_COOKIES_DATA" ]; then
    echo "Restoring YouTube cookies from environment variable..."
    echo "$YTDLP_COOKIES_DATA" | base64 -d > "$COOKIES_FILE"
    chmod 600 "$COOKIES_FILE"
    echo "YouTube cookies restored successfully."
else
    echo "WARNING: No YTDLP_COOKIES_DATA env var set. YouTube may block requests."
fi

# Start the Java application
exec java -jar app.jar
