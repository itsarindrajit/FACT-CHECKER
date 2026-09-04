#!/bin/bash
set -e

# Restore OAuth2 token from environment variable if set.
# On Render (ephemeral containers), the cached OAuth2 token file
# is lost on every deploy. We persist it as a base64-encoded env var
# and restore it at container startup.
OAUTH2_TOKEN_DIR="/home/appuser/.cache/yt-dlp-youtube-oauth2"

if [ -n "$YTDLP_OAUTH2_TOKEN_DATA" ]; then
    echo "Restoring yt-dlp OAuth2 token from environment variable..."
    mkdir -p "$OAUTH2_TOKEN_DIR"
    echo "$YTDLP_OAUTH2_TOKEN_DATA" | base64 -d > "$OAUTH2_TOKEN_DIR/token.json"
    chmod 600 "$OAUTH2_TOKEN_DIR/token.json"
    echo "OAuth2 token restored successfully."
fi

# Start the Java application
exec java -jar app.jar
