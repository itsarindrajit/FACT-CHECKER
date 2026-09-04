package com.factchecker.service;

import com.factchecker.exception.FactCheckException;
import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptList;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.stream.Collectors;

/**
 * Fetches YouTube video transcripts (auto-generated captions) directly via HTTP.
 * This completely bypasses the need to download video/audio files, avoiding
 * YouTube's download restrictions on cloud/datacenter IPs like Render.
 *
 * Falls back gracefully if no transcript is available (e.g., no captions on the video).
 */
@Slf4j
@Service
public class YouTubeTranscriptService {

    private final YoutubeTranscriptApi transcriptApi;

    public YouTubeTranscriptService() {
        this.transcriptApi = TranscriptApiFactory.createDefault();
    }

    /**
     * Attempts to fetch the transcript for a YouTube video by its video ID.
     * Tries English first, then any auto-generated language, then translates to English.
     *
     * @param videoId the 11-character YouTube video ID
     * @return Mono containing the transcript text, or Mono.empty() if unavailable
     */
    public Mono<String> fetchTranscript(String videoId) {
        return Mono.fromCallable(() -> {
            log.info("Fetching YouTube transcript for video ID: {}", videoId);

            TranscriptList transcriptList = transcriptApi.listTranscripts(videoId);

            TranscriptContent content = null;

            // Try 1: Look for manually created or auto-generated English transcript
            try {
                content = transcriptList.findTranscript("en", "en-US", "en-GB").fetch();
                log.info("Found English transcript for video {}", videoId);
            } catch (Exception e) {
                log.debug("No English transcript found for {}, trying auto-generated...", videoId);
            }

            // Try 2: Find any auto-generated transcript and translate to English
            if (content == null) {
                try {
                    var generated = transcriptList.findGeneratedTranscript("en", "en-US", "en-GB");
                    content = generated.fetch();
                    log.info("Found auto-generated English transcript for video {}", videoId);
                } catch (Exception e) {
                    log.debug("No auto-generated English transcript for {}, trying other languages...", videoId);
                }
            }

            // Try 3: Get any available transcript and translate to English
            if (content == null) {
                try {
                    var anyTranscript = transcriptList.iterator().next();
                    if (anyTranscript.isTranslatable()) {
                        content = anyTranscript.translate("en").fetch();
                        log.info("Translated transcript from {} to English for video {}",
                                anyTranscript.getLanguage(), videoId);
                    } else {
                        content = anyTranscript.fetch();
                        log.info("Using non-English transcript ({}) for video {}",
                                anyTranscript.getLanguage(), videoId);
                    }
                } catch (Exception e) {
                    log.warn("No transcript available at all for video {}: {}", videoId, e.getMessage());
                    return null;
                }
            }

            if (content == null || content.getContent().isEmpty()) {
                log.warn("Transcript content is empty for video {}", videoId);
                return null;
            }

            // Join all transcript fragments into a single text
            String fullText = content.getContent().stream()
                    .map(fragment -> fragment.getText())
                    .collect(Collectors.joining(" "));

            // Clean up common transcript artifacts
            fullText = fullText
                    .replaceAll("\\[Music\\]", "")
                    .replaceAll("\\[Applause\\]", "")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (fullText.isBlank()) {
                log.warn("Transcript text is blank after cleanup for video {}", videoId);
                return null;
            }

            log.info("YouTube transcript fetched: {} characters for video {}", fullText.length(), videoId);
            log.debug("Transcript preview: {}", fullText.substring(0, Math.min(200, fullText.length())));
            return fullText;

        }).subscribeOn(Schedulers.boundedElastic());
    }
}
