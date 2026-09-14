package com.factchecker.service;

import com.factchecker.exception.FactCheckException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptList;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fetches YouTube video transcripts/subtitles without downloading the video.
 *
 * Strategy (in order):
 * 1. Direct HTTP fetch — downloads the YouTube page with cookies, extracts caption data
 *    from the embedded ytInitialPlayerResponse JSON. Bypasses yt-dlp entirely, avoiding
 *    all player client / format selection issues on datacenter IPs.
 * 2. Java youtube-transcript-api library — fast fallback for non-blocked IPs (no cookies).
 * 3. Throws FactCheckException → orchestrator falls back to full audio download + Groq.
 */
@Slf4j
@Service
public class YouTubeTranscriptService {

    @Value("${app.ytdlp.cookies-path:}")
    private String cookiesPath;

    @Value("${app.temp-dir}")
    private String tempDir;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final YoutubeTranscriptApi transcriptApiLib = TranscriptApiFactory.createDefault();

    /**
     * Validates the cookie file on startup and logs diagnostic information.
     */
    @PostConstruct
    public void validateCookiesOnStartup() {
        if (cookiesPath == null || cookiesPath.isBlank()) {
            log.warn("No cookies path configured (app.ytdlp.cookies-path). YouTube will likely block requests.");
            return;
        }

        Path path = Paths.get(cookiesPath);
        if (!Files.exists(path)) {
            log.warn("Cookie file does not exist: {}. It may be restored at runtime by entrypoint.sh.", cookiesPath);
            return;
        }

        try {
            long size = Files.size(path);
            long lineCount = Files.lines(path).count();
            String firstLine = Files.lines(path).findFirst().orElse("(empty)");

            log.info("Cookie file validated: {} lines, {} bytes, path: {}", lineCount, size, cookiesPath);

            if (firstLine.toLowerCase().contains("cookie")) {
                log.info("Cookie file has valid Netscape header.");
            } else {
                log.warn("Cookie file first line does not look like Netscape format: '{}'",
                        firstLine.length() > 80 ? firstLine.substring(0, 80) + "..." : firstLine);
            }
        } catch (Exception e) {
            log.warn("Could not validate cookie file {}: {}", cookiesPath, e.getMessage());
        }
    }

    /**
     * Attempts to fetch the transcript for a YouTube video.
     * Throws FactCheckException if no transcript can be obtained (never returns null/empty Mono).
     */
    public Mono<String> fetchTranscript(String videoId) {
        return Mono.fromCallable(() -> {
            log.info("Fetching YouTube transcript for video ID: {}", videoId);

            // Strategy 1: Direct HTTP fetch — bypasses yt-dlp and its player client issues entirely.
            // Downloads the YouTube page with cookies, extracts caption data from the page HTML.
            String transcript = fetchViaDirectHttp(videoId);
            if (transcript != null && !transcript.isBlank()) {
                return transcript;
            }

            // Strategy 2: Java transcript API library (no cookies, works on non-blocked IPs)
            transcript = fetchViaTranscriptApi(videoId);
            if (transcript != null && !transcript.isBlank()) {
                return transcript;
            }

            // CRITICAL: Do NOT return null here. Mono.fromCallable(null) produces an empty Mono,
            // which causes the downstream flatMap to never fire, leaving the SSE stream hanging forever.
            log.warn("All transcript methods failed for video {}", videoId);
            throw new FactCheckException(
                    "Could not get transcript for this YouTube video. " +
                    "YouTube may be blocking requests from this server. " +
                    "The video may be private, age-restricted, or region-locked.");

        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ========================================================================
    // Strategy 1: Direct HTTP — fetch page with cookies, parse captions from HTML
    // ========================================================================

    /**
     * Fetches subtitles directly via HTTP with cookie authentication.
     * This completely bypasses yt-dlp, avoiding all player client / format selection
     * issues that plague datacenter IPs.
     *
     * How it works:
     * 1. Load cookies from the Netscape cookie file
     * 2. GET the YouTube page with cookies (this always succeeds with valid cookies)
     * 3. Extract the ytInitialPlayerResponse JSON embedded in the page HTML
     * 4. Parse caption track URLs from the JSON
     * 5. Download the actual caption content
     * 6. Parse and return plain text
     */
    private String fetchViaDirectHttp(String videoId) {
        try {
            log.debug("Trying direct HTTP subtitle fetch for video {}", videoId);

            // Step 1: Build cookie header from Netscape cookie file
            String cookieHeader = buildCookieHeader();
            if (cookieHeader == null || cookieHeader.isBlank()) {
                log.debug("No cookies available for direct HTTP fetch");
                return null;
            }

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            // Step 2: Fetch the YouTube page with cookies
            HttpRequest pageRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.youtube.com/watch?v=" + videoId))
                    .header("Cookie", cookieHeader)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> pageResponse = client.send(pageRequest, HttpResponse.BodyHandlers.ofString());

            if (pageResponse.statusCode() != 200) {
                log.debug("YouTube page returned status {} for video {}", pageResponse.statusCode(), videoId);
                return null;
            }

            String html = pageResponse.body();
            log.debug("YouTube page fetched: {} chars for video {}", html.length(), videoId);

            // Step 3: Extract ytInitialPlayerResponse JSON from HTML
            String playerResponseJson = extractJsonFromHtml(html, "ytInitialPlayerResponse");
            if (playerResponseJson == null) {
                log.debug("Could not find ytInitialPlayerResponse in page for video {}", videoId);
                return null;
            }

            // Step 4: Parse caption tracks from the JSON
            JsonNode playerResponse = objectMapper.readTree(playerResponseJson);
            JsonNode captionTracks = playerResponse
                    .path("captions")
                    .path("playerCaptionsTracklistRenderer")
                    .path("captionTracks");

            if (captionTracks.isMissingNode() || !captionTracks.isArray() || captionTracks.isEmpty()) {
                log.debug("No caption tracks found in playerResponse for video {}", videoId);
                return null;
            }

            log.debug("Found {} caption tracks for video {}", captionTracks.size(), videoId);

            // Step 5: Find the best English caption track
            String captionUrl = findEnglishCaptionUrl(captionTracks);
            if (captionUrl == null) {
                log.debug("No suitable caption URL found for video {}", videoId);
                return null;
            }

            // Request JSON3 format for easier parsing
            String separator = captionUrl.contains("?") ? "&" : "?";
            captionUrl = captionUrl + separator + "fmt=json3";

            // Step 6: Download the caption content
            HttpRequest captionRequest = HttpRequest.newBuilder()
                    .uri(URI.create(captionUrl))
                    .header("Cookie", cookieHeader)
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> captionResponse = client.send(captionRequest, HttpResponse.BodyHandlers.ofString());

            if (captionResponse.statusCode() != 200) {
                log.debug("Caption download returned status {} for video {}", captionResponse.statusCode(), videoId);
                return null;
            }

            // Step 7: Parse the caption JSON3 format to plain text
            String text = parseCaptionJson3(captionResponse.body());

            if (text != null && !text.isBlank()) {
                log.info("YouTube transcript via direct HTTP: {} chars for video {}", text.length(), videoId);
                return text;
            }

            return null;
        } catch (Exception e) {
            log.debug("Direct HTTP subtitle fetch error for {}: {}", videoId, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a JSON object assigned to a variable name from YouTube page HTML.
     * Uses brace-counting to correctly handle nested JSON.
     */
    private String extractJsonFromHtml(String html, String variableName) {
        // Try patterns: "var NAME = {...};" and "NAME = {...};"
        int startIdx = html.indexOf(variableName);
        if (startIdx == -1) return null;

        // Find the first '{' after the variable name
        int jsonStart = html.indexOf('{', startIdx);
        if (jsonStart == -1) return null;

        // Use brace-counting to find the matching closing '}'
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int jsonEnd = -1;

        for (int i = jsonStart; i < html.length(); i++) {
            char c = html.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (inString) continue;

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    jsonEnd = i + 1;
                    break;
                }
            }
        }

        if (jsonEnd == -1) return null;
        return html.substring(jsonStart, jsonEnd);
    }

    /**
     * Finds the best English caption URL from a list of caption tracks.
     * Prefers manual English captions, then auto-generated, then any language.
     */
    private String findEnglishCaptionUrl(JsonNode captionTracks) {
        String englishUrl = null;
        String anyUrl = null;

        for (JsonNode track : captionTracks) {
            String langCode = track.path("languageCode").asText("");
            String kind = track.path("kind").asText("");
            String baseUrl = track.path("baseUrl").asText(null);

            if (baseUrl == null) continue;

            if (langCode.startsWith("en")) {
                if (!"asr".equals(kind)) {
                    // Prefer manual English captions (not auto-generated)
                    return baseUrl;
                }
                if (englishUrl == null) {
                    englishUrl = baseUrl;
                }
            }

            if (anyUrl == null) {
                anyUrl = baseUrl;
            }
        }

        return englishUrl != null ? englishUrl : anyUrl;
    }

    /**
     * Parses YouTube's JSON3 caption format into plain text.
     * JSON3 structure: { "events": [{ "segs": [{ "utf8": "text" }] }] }
     */
    private String parseCaptionJson3(String jsonContent) {
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            JsonNode events = root.path("events");
            if (events.isMissingNode() || !events.isArray()) return null;

            StringBuilder text = new StringBuilder();
            for (JsonNode event : events) {
                JsonNode segs = event.path("segs");
                if (segs.isArray()) {
                    for (JsonNode seg : segs) {
                        String segText = seg.path("utf8").asText("");
                        if (!segText.isBlank() && !segText.equals("\n")) {
                            text.append(segText).append(" ");
                        }
                    }
                }
            }

            return text.toString()
                    .replaceAll("\\[Music\\]", "")
                    .replaceAll("\\[Applause\\]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (Exception e) {
            log.debug("Failed to parse caption JSON3: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds an HTTP Cookie header string from the Netscape cookie file.
     * Only includes cookies for youtube.com and google.com domains.
     */
    private String buildCookieHeader() {
        if (cookiesPath == null || cookiesPath.isBlank()) return null;

        Path path = Paths.get(cookiesPath);
        if (!Files.exists(path)) return null;

        try {
            List<String> cookies = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\t");
                if (parts.length >= 7) {
                    String domain = parts[0];
                    String name = parts[5];
                    String value = parts[6];

                    // Only include YouTube/Google cookies
                    if (domain.contains("youtube.com") || domain.contains(".google.com")) {
                        cookies.add(name + "=" + value);
                    }
                }
            }

            String header = String.join("; ", cookies);
            log.debug("Built cookie header with {} cookies", cookies.size());
            return header.isEmpty() ? null : header;
        } catch (Exception e) {
            log.debug("Failed to parse cookie file: {}", e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Strategy 2: Java youtube-transcript-api (no cookies, for non-blocked IPs)
    // ========================================================================

    /**
     * Uses the Java youtube-transcript-api library (no cookies, fast).
     * Works when YouTube isn't blocking the server IP.
     */
    private String fetchViaTranscriptApi(String videoId) {
        try {
            log.debug("Trying Java transcript API for video {}", videoId);
            TranscriptList transcriptList = transcriptApiLib.listTranscripts(videoId);

            TranscriptContent content = null;

            // Try English transcript
            try {
                content = transcriptList.findTranscript("en", "en-US", "en-GB").fetch();
            } catch (Exception ignored) {}

            // Try auto-generated English
            if (content == null) {
                try {
                    content = transcriptList.findGeneratedTranscript("en", "en-US", "en-GB").fetch();
                } catch (Exception ignored) {}
            }

            // Try any available transcript and translate
            if (content == null) {
                try {
                    var anyTranscript = transcriptList.iterator().next();
                    content = anyTranscript.isTranslatable()
                            ? anyTranscript.translate("en").fetch()
                            : anyTranscript.fetch();
                } catch (Exception e) {
                    log.debug("No transcript via API for {}: {}", videoId, e.getMessage());
                    return null;
                }
            }

            if (content == null || content.getContent().isEmpty()) return null;

            String text = content.getContent().stream()
                    .map(f -> f.getText())
                    .collect(Collectors.joining(" "))
                    .replaceAll("\\[Music\\]", "")
                    .replaceAll("\\[Applause\\]", "")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (!text.isBlank()) {
                log.info("YouTube transcript via API: {} chars for video {}", text.length(), videoId);
            }
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.debug("Java transcript API failed for {}: {}", videoId, e.getMessage());
            return null;
        }
    }
}
