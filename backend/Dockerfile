# Stage 1: Build the application
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml first for dependency caching
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src src
RUN mvn package -DskipTests -B

# Stage 2: Runtime image with Python, yt-dlp, ffmpeg, and OAuth2 plugin
FROM eclipse-temurin:21-jre

# Install python3, pip, ffmpeg, nodejs, yt-dlp with OAuth2 + PO Token plugins
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    nodejs \
    ffmpeg \
    && pip3 install --no-cache-dir -U --break-system-packages \
       yt-dlp \
       yt-dlp-youtube-oauth2 \
       bgutil-ytdlp-pot-provider \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser -m appuser

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Copy entrypoint script
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Create temp directory for audio files and OAuth2 cache directory
RUN mkdir -p /tmp/fact-checker-audio && chown appuser:appuser /tmp/fact-checker-audio \
    && mkdir -p /home/appuser/.cache/yt-dlp-youtube-oauth2 \
    && chown -R appuser:appuser /home/appuser/.cache

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# Run the application via entrypoint (restores OAuth2 token from env var)
ENTRYPOINT ["/app/entrypoint.sh"]
