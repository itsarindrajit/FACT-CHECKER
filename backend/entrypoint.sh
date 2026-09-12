#!/bin/bash
set -e

COOKIES_FILE="/home/appuser/cookies.txt"

# --- Cookie Restoration ---
# Supports two methods (in order of priority):
#
# 1. DIRECT FILE: Render "Secret Files" feature — upload cookies.txt directly
#    at /etc/secrets/cookies.txt (no base64 needed). Set YTDLP_COOKIES_PATH
#    env var to this path, or it will be auto-detected.
#
# 2. BASE64 ENV VAR: Traditional approach — base64-encode cookies.txt and store
#    as YTDLP_COOKIES_DATA env var. Decoded and written at startup.

# Method 1: Check for directly uploaded cookie file (Render Secret Files)
DIRECT_COOKIE_PATH="${YTDLP_COOKIES_SECRET_PATH:-/etc/secrets/cookies.txt}"
if [ -f "$DIRECT_COOKIE_PATH" ] && [ -s "$DIRECT_COOKIE_PATH" ]; then
    echo "Found direct cookie file at $DIRECT_COOKIE_PATH. Copying to $COOKIES_FILE..."
    cp "$DIRECT_COOKIE_PATH" "$COOKIES_FILE"

# Method 2: Restore from base64-encoded environment variable
elif [ -n "$YTDLP_COOKIES_DATA" ]; then
    echo "Restoring YouTube cookies from YTDLP_COOKIES_DATA environment variable..."
    echo "$YTDLP_COOKIES_DATA" | base64 -d > "$COOKIES_FILE"

else
    echo "WARNING: No cookie source found. YouTube will likely block requests."
    echo "  Option A: Upload cookies.txt via Render Secret Files at $DIRECT_COOKIE_PATH"
    echo "  Option B: Set YTDLP_COOKIES_DATA env var with base64-encoded cookies"
fi

# --- Sanitize the cookie file (if it exists) ---
if [ -f "$COOKIES_FILE" ] && [ -s "$COOKIES_FILE" ]; then
    # Strip UTF-8 BOM (EF BB BF) if present
    sed -i '1s/^\xEF\xBB\xBF//' "$COOKIES_FILE"

    # Convert Windows line endings (\r\n) to Unix (\n)
    if command -v dos2unix &> /dev/null; then
        dos2unix -q "$COOKIES_FILE" 2>/dev/null || true
    else
        sed -i 's/\r$//' "$COOKIES_FILE"
    fi

    # Remove any blank lines at the start of the file
    sed -i '/./,$!d' "$COOKIES_FILE"

    chmod 600 "$COOKIES_FILE"

    # --- Validate the cookie file ---
    COOKIE_LINES=$(wc -l < "$COOKIES_FILE" | tr -d ' ')
    COOKIE_SIZE=$(wc -c < "$COOKIES_FILE" | tr -d ' ')
    FIRST_LINE=$(head -n 1 "$COOKIES_FILE")

    echo "Cookie file stats: ${COOKIE_LINES} lines, ${COOKIE_SIZE} bytes"

    if echo "$FIRST_LINE" | grep -qi "cookie"; then
        echo "Cookie file validated: Netscape header found."
    elif echo "$FIRST_LINE" | grep -qP '^\S+\t'; then
        echo "Cookie file validated: Tab-separated domain entry found (no header comment)."
    else
        echo "WARNING: Cookie file may be invalid! First line: '${FIRST_LINE:0:80}'"
        echo "WARNING: Expected Netscape cookie format. YouTube auth may fail."
    fi

    echo "YouTube cookies restored and sanitized successfully."
fi

# Update yt-dlp to latest version on every startup.
# YouTube changes its anti-bot detection frequently and yt-dlp pushes fixes within hours.
echo "Updating yt-dlp to latest version..."
pip3 install -U --break-system-packages yt-dlp 2>&1 | tail -1 || echo "WARNING: yt-dlp update failed, using installed version."
echo "yt-dlp version: $(yt-dlp --version)"

# Start the Java application
exec java -jar app.jar
