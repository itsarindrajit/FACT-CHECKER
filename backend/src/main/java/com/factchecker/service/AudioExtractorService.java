package com.factchecker.service;

import com.factchecker.exception.FactCheckException;
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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Downloads audio from YouTube Shorts and Instagram Reels using yt-dlp.
 * Runs yt-dlp as a subprocess via ProcessBuilder, extracting audio-only in mp3 format.
 */
@Slf4j
@Service
public class AudioExtractorService {

    @Value("${app.temp-dir}")
    private String tempDir;

    /**
     * Downloads audio from the given URL and returns the path to the audio file.
     * Runs on a bounded elastic scheduler to avoid blocking the event loop.
     */
    public Mono<Path> extractAudio(String url) {
        return Mono.fromCallable(() -> {
            // Ensure temp directory exists
            Path tempPath = Paths.get(tempDir);
            Files.createDirectories(tempPath);

            String filename = UUID.randomUUID().toString();
            Path outputTemplate = tempPath.resolve(filename + ".%(ext)s");
            Path expectedOutput = tempPath.resolve(filename + ".mp3");

            log.info("Extracting audio from URL: {} to {}", url, expectedOutput);

            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "-x",                          // Extract audio only
                    "--audio-format", "mp3",        // Convert to mp3
                    "--audio-quality", "5",         // Medium quality (saves bandwidth)
                    "--no-playlist",                // Don't download playlists
                    "--no-warnings",                // Suppress warnings
                    "--max-filesize", "25m",        // Max 25MB (Groq limit)
                    "-o", outputTemplate.toString(),
                    url
            );

            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Capture output for debugging
            String processOutput;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                processOutput = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.error("yt-dlp failed with exit code {}: {}", exitCode, processOutput);
                throw new FactCheckException(
                        "Failed to download audio. yt-dlp exit code: " + exitCode +
                        ". Reason: " + (processOutput.length() > 200 ? processOutput.substring(0, 200) + "..." : processOutput));
            }

            // yt-dlp may produce a file with a slightly different extension, find it
            if (Files.exists(expectedOutput)) {
                log.info("Audio extracted successfully: {} ({} bytes)", expectedOutput, Files.size(expectedOutput));
                return expectedOutput;
            }

            // Search for any file with the UUID prefix
            Path found = Files.list(tempPath)
                    .filter(p -> p.getFileName().toString().startsWith(filename))
                    .findFirst()
                    .orElseThrow(() -> new FactCheckException(
                            "Audio file not found after download. Output: " + processOutput));

            log.info("Audio extracted (alternate ext): {} ({} bytes)", found, Files.size(found));
            return found;

        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Deletes the temporary audio file.
     */
    public Mono<Void> cleanup(Path audioPath) {
        return Mono.fromRunnable(() -> {
            try {
                if (audioPath != null && Files.exists(audioPath)) {
                    Files.delete(audioPath);
                    log.debug("Cleaned up temp file: {}", audioPath);
                }
            } catch (Exception e) {
                log.warn("Failed to cleanup temp file: {}", audioPath, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
