#!/bin/bash
set -e

COOKIES_FILE="/home/appuser/cookies.txt"

# Restore YouTube cookies from environment variable if set.
# On Render (ephemeral containers), files are lost on every deploy.
# We persist cookies as a base64-encoded env var and restore at startup.
if [ -n "$YTDLP_COOKIES_DATA" ]; then
    echo "Restoring YouTube cookies from environment variable..."
    echo "$YTDLP_COOKIES_DATA" | base64 -d > "$COOKIES_FILE"

    # --- Sanitize the cookie file ---
    # PowerShell on Windows can introduce BOM bytes and \r\n line endings
    # which silently corrupt the Netscape cookie format that yt-dlp expects.

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

    # Netscape cookie files should start with "# Netscape HTTP Cookie File" or "# HTTP Cookie File"
    # or the first data line should be a tab-separated domain entry starting with . or a domain
    if echo "$FIRST_LINE" | grep -qi "cookie"; then
        echo "Cookie file validated: Netscape header found."
    elif echo "$FIRST_LINE" | grep -qP '^\S+\t'; then
        echo "Cookie file validated: Tab-separated domain entry found (no header comment)."
    else
        echo "WARNING: Cookie file may be invalid! First line: '${FIRST_LINE:0:80}'"
        echo "WARNING: Expected Netscape cookie format. YouTube auth may fail."
    fi

    echo "YouTube cookies restored and sanitized successfully."
else
    echo "WARNING: No YTDLP_COOKIES_DATA env var set. YouTube may block requests."
fi

# Update yt-dlp to latest version on every startup.
# YouTube changes its anti-bot detection frequently and yt-dlp pushes fixes within hours.
echo "Updating yt-dlp to latest version..."
pip3 install -U --break-system-packages yt-dlp 2>&1 | tail -1 || echo "WARNING: yt-dlp update failed, using installed version."
echo "yt-dlp version: $(yt-dlp --version)"

# Start the Java application
exec java -jar app.jar
