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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches YouTube video transcripts/subtitles without downloading the video.
 *
 * Strategy (in order):
 * 1. Direct HTTP fetch — downloads the YouTube page with cookies, extracts caption data
 *    from the embedded ytInitialPlayerResponse JSON, then downloads subtitle content
 *    using merged session cookies (original + page response Set-Cookie headers).
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
            String transcript = fetchViaDirectHttp(videoId);
            if (transcript != null && !transcript.isBlank()) {
                return transcript;
            }

            // Strategy 2: Java transcript API library (no cookies, works on non-blocked IPs)
            transcript = fetchViaTranscriptApi(videoId);
            if (transcript != null && !transcript.isBlank()) {
                return transcript;
            }

            // CRITICAL: Do NOT return null. Mono.fromCallable(null) produces an empty Mono.
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
     * Completely bypasses yt-dlp to avoid player client / format selection issues.
     *
     * Critical detail: YouTube's timedtext API requires BOTH the original cookies AND
     * fresh session cookies set via Set-Cookie headers in the page response. We must
     * merge them before making the caption download request.
     */
    private String fetchViaDirectHttp(String videoId) {
        try {
            log.debug("Trying direct HTTP subtitle fetch for video {}", videoId);

            // Step 1: Build cookie header from Netscape cookie file
            String originalCookieHeader = buildCookieHeader();
            if (originalCookieHeader == null || originalCookieHeader.isBlank()) {
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
                    .header("Cookie", originalCookieHeader)
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

            // Step 3: CRITICAL — merge original cookies with new session cookies from page response.
            // YouTube's timedtext API returns empty body (200 OK, 0 bytes) without these fresh cookies.
            String mergedCookieHeader = mergeCookiesFromResponse(originalCookieHeader, pageResponse);
            int newCookieCount = countCookies(mergedCookieHeader) - countCookies(originalCookieHeader);
            log.debug("Merged cookies: {} original + {} new from Set-Cookie = {} total",
                    countCookies(originalCookieHeader), Math.max(0, newCookieCount), countCookies(mergedCookieHeader));

            // Step 4: Extract ytInitialPlayerResponse JSON from HTML
            String playerResponseJson = extractJsonFromHtml(html, "ytInitialPlayerResponse");
            if (playerResponseJson == null) {
                log.debug("Could not find ytInitialPlayerResponse in page for video {}", videoId);
                return null;
            }

            // Step 5: Parse caption tracks from the JSON
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

            // Log all available caption tracks for debugging
            for (JsonNode track : captionTracks) {
                log.debug("Caption track: lang={}, kind={}, name={}, baseUrl={}",
                        track.path("languageCode").asText("?"),
                        track.path("kind").asText("?"),
                        track.path("name").path("simpleText").asText(track.path("name").asText("?")),
                        truncateUrl(track.path("baseUrl").asText("")));
            }

            // Step 6: Find the best caption track URL
            String captionUrl = findEnglishCaptionUrl(captionTracks);
            if (captionUrl == null) {
                log.debug("No suitable caption URL found for video {}", videoId);
                return null;
            }

            // Ensure absolute URL (YouTube sometimes uses relative URLs)
            if (captionUrl.startsWith("/")) {
                captionUrl = "https://www.youtube.com" + captionUrl;
            }

            log.debug("Using caption URL: {}", truncateUrl(captionUrl));

            // Step 7: Try downloading captions with merged cookies in multiple formats
            String text = downloadCaptions(client, captionUrl, mergedCookieHeader, videoId, "json3");

            if (text == null || text.isBlank()) {
                log.debug("JSON3 format returned no text, trying srv3 (XML) for video {}", videoId);
                text = downloadCaptions(client, captionUrl, mergedCookieHeader, videoId, "srv3");
            }

            // Try without fmt parameter (default YouTube format)
            if (text == null || text.isBlank()) {
                log.debug("Trying default format (no fmt param) for video {}", videoId);
                text = downloadCaptionsRaw(client, captionUrl, mergedCookieHeader, videoId);
            }

            // If still no text, try with English translation (for non-English captions)
            if (text == null || text.isBlank()) {
                log.debug("Trying English translation for video {}", videoId);
                String tlangUrl = appendParam(captionUrl, "tlang", "en");
                text = downloadCaptions(client, tlangUrl, mergedCookieHeader, videoId, "json3");
            }

            if (text != null && !text.isBlank()) {
                log.info("YouTube transcript via direct HTTP: {} chars for video {}", text.length(), videoId);
                return text;
            }

            log.debug("Direct HTTP fetch produced no usable text for video {}", videoId);
            return null;
        } catch (Exception e) {
            log.warn("Direct HTTP subtitle fetch error for {}: {} ({})", videoId, e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Merges original cookies with new cookies from Set-Cookie response headers.
     * New cookies override existing ones with the same name.
     */
    private String mergeCookiesFromResponse(String originalCookieHeader, HttpResponse<?> response) {
        // Parse original cookies into an ordered map
        Map<String, String> cookieMap = new LinkedHashMap<>();
        if (originalCookieHeader != null) {
            for (String pair : originalCookieHeader.split("; ")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    cookieMap.put(pair.substring(0, eq).trim(), pair.substring(eq + 1));
                }
            }
        }

        // Parse Set-Cookie headers from response and merge
        List<String> setCookieHeaders = response.headers().allValues("set-cookie");
        log.debug("Page response contained {} Set-Cookie headers", setCookieHeaders.size());

        for (String setCookie : setCookieHeaders) {
            // Set-Cookie format: "name=value; Path=/; Domain=.youtube.com; Secure; HttpOnly"
            // We only need the name=value part (before the first semicolon)
            String nameValue = setCookie.split(";")[0].trim();
            int eq = nameValue.indexOf('=');
            if (eq > 0) {
                String name = nameValue.substring(0, eq).trim();
                String value = nameValue.substring(eq + 1);
                cookieMap.put(name, value);
                log.debug("Set-Cookie merged: {}={}", name,
                        value.length() > 30 ? value.substring(0, 30) + "..." : value);
            }
        }

        // Rebuild cookie header
        return cookieMap.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    /**
     * Counts cookies in a cookie header string.
     */
    private int countCookies(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) return 0;
        return cookieHeader.split("; ").length;
    }

    /**
     * Downloads and parses captions from the given URL in the specified format.
     */
    private String downloadCaptions(HttpClient client, String baseUrl, String cookieHeader,
                                     String videoId, String format) {
        try {
            String url = appendParam(baseUrl, "fmt", format);

            HttpRequest captionRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Cookie", cookieHeader)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.youtube.com/watch?v=" + videoId)
                    .header("Origin", "https://www.youtube.com")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(captionRequest, HttpResponse.BodyHandlers.ofString());

            log.debug("Caption download (fmt={}) status={}, body length={} for video {}",
                    format, response.statusCode(), response.body().length(), videoId);

            if (response.statusCode() != 200 || response.body().isEmpty()) {
                return null;
            }

            String body = response.body();
            log.debug("Caption response preview (fmt={}): {}", format,
                    body.length() > 200 ? body.substring(0, 200) + "..." : body);

            // Parse based on format
            String text;
            if ("json3".equals(format)) {
                text = parseCaptionJson3(body);
            } else {
                text = parseCaptionXml(body);
            }

            if (text != null && !text.isBlank()) {
                log.debug("Parsed {} chars of transcript text (fmt={}) for video {}", text.length(), format, videoId);
            } else {
                log.debug("Caption parsing returned empty text (fmt={}) for video {}", format, videoId);
            }

            return text;
        } catch (Exception e) {
            log.debug("Caption download error (fmt={}) for {}: {} ({})",
                    format, videoId, e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Downloads captions from the raw URL (no fmt parameter) and tries to auto-detect format.
     */
    private String downloadCaptionsRaw(HttpClient client, String baseUrl, String cookieHeader, String videoId) {
        try {
            HttpRequest captionRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Cookie", cookieHeader)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.youtube.com/watch?v=" + videoId)
                    .header("Origin", "https://www.youtube.com")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(captionRequest, HttpResponse.BodyHandlers.ofString());

            log.debug("Caption download (raw) status={}, body length={} for video {}",
                    response.statusCode(), response.body().length(), videoId);

            if (response.statusCode() != 200 || response.body().isEmpty()) {
                return null;
            }

            String body = response.body();
            log.debug("Caption raw response preview: {}",
                    body.length() > 300 ? body.substring(0, 300) + "..." : body);

            // Auto-detect format: JSON starts with {, XML starts with < or <?
            String text;
            if (body.trim().startsWith("{")) {
                text = parseCaptionJson3(body);
            } else {
                text = parseCaptionXml(body);
            }

            if (text != null && !text.isBlank()) {
                log.debug("Parsed {} chars of transcript text (raw) for video {}", text.length(), videoId);
            }

            return text;
        } catch (Exception e) {
            log.debug("Caption raw download error for {}: {} ({})",
                    videoId, e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Extracts a JSON object assigned to a variable name from YouTube page HTML.
     * Uses brace-counting to correctly handle nested JSON.
     */
    private String extractJsonFromHtml(String html, String variableName) {
        int startIdx = html.indexOf(variableName);
        if (startIdx == -1) return null;

        int jsonStart = html.indexOf('{', startIdx);
        if (jsonStart == -1) return null;

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
     * Finds the best caption URL from a list of caption tracks.
     * Priority: manual English > auto-generated English > any language.
     */
    private String findEnglishCaptionUrl(JsonNode captionTracks) {
        String englishManualUrl = null;
        String englishAutoUrl = null;
        String anyUrl = null;

        for (JsonNode track : captionTracks) {
            String langCode = track.path("languageCode").asText("");
            String kind = track.path("kind").asText("");
            String baseUrl = track.path("baseUrl").asText(null);

            if (baseUrl == null || baseUrl.isBlank()) continue;

            if (langCode.startsWith("en")) {
                if (!"asr".equals(kind) && englishManualUrl == null) {
                    englishManualUrl = baseUrl;
                } else if (englishAutoUrl == null) {
                    englishAutoUrl = baseUrl;
                }
            }

            if (anyUrl == null) {
                anyUrl = baseUrl;
            }
        }

        if (englishManualUrl != null) return englishManualUrl;
        if (englishAutoUrl != null) return englishAutoUrl;
        return anyUrl;
    }

    /**
     * Parses YouTube's JSON3 caption format into plain text.
     */
    private String parseCaptionJson3(String jsonContent) {
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            JsonNode events = root.path("events");
            if (events.isMissingNode() || !events.isArray()) {
                log.debug("JSON3: 'events' field missing or not an array");
                return null;
            }

            log.debug("JSON3: found {} events", events.size());

            StringBuilder text = new StringBuilder();
            int segCount = 0;
            for (JsonNode event : events) {
                JsonNode segs = event.path("segs");
                if (segs.isArray()) {
                    for (JsonNode seg : segs) {
                        String segText = seg.path("utf8").asText("");
                        if (!segText.isBlank() && !segText.equals("\n")) {
                            text.append(segText).append(" ");
                            segCount++;
                        }
                    }
                }
            }

            log.debug("JSON3: extracted {} non-empty segments", segCount);

            return text.toString()
                    .replaceAll("\\[Music\\]", "")
                    .replaceAll("\\[Applause\\]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (Exception e) {
            log.debug("Failed to parse caption JSON3: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Parses YouTube's srv3 XML caption format into plain text.
     */
    private String parseCaptionXml(String xmlContent) {
        try {
            StringBuilder text = new StringBuilder();
            int idx = 0;
            int segCount = 0;

            while (idx < xmlContent.length()) {
                int tagStart = xmlContent.indexOf("<text", idx);
                if (tagStart == -1) break;

                int contentStart = xmlContent.indexOf(">", tagStart);
                if (contentStart == -1) break;
                contentStart++;

                int contentEnd = xmlContent.indexOf("</text>", contentStart);
                if (contentEnd == -1) break;

                String segText = xmlContent.substring(contentStart, contentEnd)
                        .replaceAll("<[^>]+>", "")
                        .trim();

                segText = decodeHtmlEntities(segText);

                if (!segText.isBlank()) {
                    text.append(segText).append(" ");
                    segCount++;
                }

                idx = contentEnd + 7;
            }

            log.debug("XML (srv3): extracted {} text segments", segCount);

            return text.toString()
                    .replaceAll("\\[Music\\]", "")
                    .replaceAll("\\[Applause\\]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (Exception e) {
            log.debug("Failed to parse caption XML: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Decodes common HTML entities in caption text.
     */
    private String decodeHtmlEntities(String text) {
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/");
    }

    private String appendParam(String url, String key, String value) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + key + "=" + value;
    }

    private String truncateUrl(String url) {
        if (url == null) return "null";
        if (url.length() <= 120) return url;
        return url.substring(0, 120) + "...[truncated]";
    }

    /**
     * Builds an HTTP Cookie header string from the Netscape cookie file.
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

    private String fetchViaTranscriptApi(String videoId) {
        try {
            log.debug("Trying Java transcript API for video {}", videoId);
            TranscriptList transcriptList = transcriptApiLib.listTranscripts(videoId);

            TranscriptContent content = null;

            try {
                content = transcriptList.findTranscript("en", "en-US", "en-GB").fetch();
            } catch (Exception ignored) {}

            if (content == null) {
                try {
                    content = transcriptList.findGeneratedTranscript("en", "en-US", "en-GB").fetch();
                } catch (Exception ignored) {}
            }

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
