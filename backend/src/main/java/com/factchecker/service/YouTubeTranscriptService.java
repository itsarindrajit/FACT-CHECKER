package com.factchecker.service;

import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptList;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import com.factchecker.exception.FactCheckException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fetches YouTube video transcripts/subtitles without downloading the video.
 *
 * Strategy (in order):
 * 1. yt-dlp subtitle extraction (--write-subs --skip-download) with cookies — most reliable on Render
 * 2. Java youtube-transcript-api library — fast fallback for non-blocked IPs
 * 3. Throws FactCheckException → orchestrator falls back to full audio download + Groq
 */
@Slf4j
@Service
public class YouTubeTranscriptService {

    @Value("${app.ytdlp.cookies-path:}")
    private String cookiesPath;

    @Value("${app.temp-dir}")
    private String tempDir;

    /** Timeout for yt-dlp subtitle extraction process (seconds). */
    private static final int SUBTITLE_PROCESS_TIMEOUT_SECONDS = 60;

    private final YoutubeTranscriptApi transcriptApiLib = TranscriptApiFactory.createDefault();

    // Pattern to match SRT timestamps like "00:00:01,234 --> 00:00:03,456"
    private static final Pattern SRT_TIMESTAMP = Pattern.compile("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}");
    // Pattern to match SRT sequence numbers (just digits on a line)
    private static final Pattern SRT_INDEX = Pattern.compile("^\\d+$");

    /**
     * Validates the cookie file on startup and logs diagnostic information.
     * This helps debug cookie-related authentication failures from the logs.
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

            // Strategy 1: yt-dlp subtitle extraction with cookies (most reliable on Render)
            String transcript = fetchViaYtDlpSubtitles(videoId);
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

    /**
     * Uses yt-dlp to extract subtitles WITHOUT downloading the video.
     * This leverages yt-dlp's battle-tested cookie handling.
     */
    private String fetchViaYtDlpSubtitles(String videoId) {
        try {
            Path tempPath = Paths.get(tempDir);
            Files.createDirectories(tempPath);

            String filename = "subs-" + UUID.randomUUID().toString();
            Path outputTemplate = tempPath.resolve(filename);

            List<String> command = new ArrayList<>(List.of(
                    "yt-dlp",
                    "--write-subs",              // Download subtitles
                    "--write-auto-subs",          // Include auto-generated subs
                    "--sub-langs", "en.*,-live_chat",  // English variants, exclude live chat
                    "--skip-download",            // Don't download the video
                    "--convert-subs", "srt",      // Convert to SRT format
                    "--no-playlist",
                    "--no-warnings",
                    "--socket-timeout", "30",     // 30s network timeout per request
                    "--retries", "2"              // Only retry twice (prevent infinite retry loops)
            ));

            // Add cookies for authentication
            if (cookiesPath != null && !cookiesPath.isBlank() && Files.exists(Paths.get(cookiesPath))) {
                command.add("--cookies");
                command.add(cookiesPath);
            }

            command.add("-o");
            command.add(outputTemplate.toString());
            command.add("https://www.youtube.com/watch?v=" + videoId);

            log.debug("Executing yt-dlp subtitle command: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean completed = process.waitFor(SUBTITLE_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                log.error("yt-dlp subtitle extraction TIMED OUT after {}s for video {}. Force-killing process.",
                        SUBTITLE_PROCESS_TIMEOUT_SECONDS, videoId);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS); // Give it a moment to die
                cleanupSubFiles(tempPath, filename);
                return null;
            }

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.debug("yt-dlp subtitle extraction failed (exit {}): {}", exitCode, output);
                return null;
            }

            // Find the generated .srt file
            Path srtFile = Files.list(tempPath)
                    .filter(p -> p.getFileName().toString().startsWith(filename))
                    .filter(p -> p.toString().endsWith(".srt"))
                    .findFirst()
                    .orElse(null);

            if (srtFile == null) {
                log.debug("No SRT file produced by yt-dlp for video {}", videoId);
                // Clean up any non-srt files that were created
                cleanupSubFiles(tempPath, filename);
                return null;
            }

            // Parse SRT to plain text
            String srtContent = Files.readString(srtFile);
            String text = parseSrtToText(srtContent);

            // Clean up subtitle files
            cleanupSubFiles(tempPath, filename);

            if (text != null && !text.isBlank()) {
                log.info("YouTube transcript via yt-dlp subtitles: {} chars for video {}", text.length(), videoId);
                return text;
            }

            return null;
        } catch (Exception e) {
            log.debug("yt-dlp subtitle extraction error for {}: {}", videoId, e.getMessage());
            return null;
        }
    }

    /**
     * Parses SRT content into plain text, removing timestamps, sequence numbers,
     * and deduplicating repeated lines from auto-generated subtitles.
     */
    private String parseSrtToText(String srtContent) {
        List<String> textLines = srtContent.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !SRT_INDEX.matcher(line).matches())
                .filter(line -> !SRT_TIMESTAMP.matcher(line).matches())
                .map(line -> line.replaceAll("<[^>]+>", ""))  // Remove HTML tags
                .filter(line -> !line.isBlank())
                .collect(Collectors.toList());

        // Deduplicate: auto-generated subs repeat text across overlapping timestamps
        List<String> deduped = new ArrayList<>();
        String lastLine = "";
        for (String line : textLines) {
            if (!line.equals(lastLine)) {
                deduped.add(line);
                lastLine = line;
            }
        }

        return String.join(" ", deduped)
                .replaceAll("\\[Music\\]", "")
                .replaceAll("\\[Applause\\]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Clean up temporary subtitle files.
     */
    private void cleanupSubFiles(Path tempPath, String filenamePrefix) {
        try {
            Files.list(tempPath)
                    .filter(p -> p.getFileName().toString().startsWith(filenamePrefix))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

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
