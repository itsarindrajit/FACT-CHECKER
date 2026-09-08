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
fi

# Restore OAuth2 token if set (legacy support)
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
