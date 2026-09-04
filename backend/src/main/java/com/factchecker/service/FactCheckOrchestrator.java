package com.factchecker.service;

import com.factchecker.model.*;
import com.factchecker.util.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the entire fact-checking pipeline, emitting SSE progress events at each stage.
 * Pipeline: Validate URL → Get Transcript → Extract Claims → Search Web → Verdict
 *
 * For YouTube URLs: fetches transcript directly (no video download needed).
 * For Instagram URLs: downloads audio via yt-dlp, then transcribes with Groq Whisper.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactCheckOrchestrator {

    private final AudioExtractorService audioExtractor;
    private final TranscriptionService transcriptionService;
    private final YouTubeTranscriptService youTubeTranscriptService;
    private final ClaimExtractorService claimExtractor;
    private final SearchService searchService;
    private final VerdictService verdictService;

    /**
     * Runs the full pipeline and returns a Flux of progress events.
     * The final event contains the complete FactCheckReport.
     */
    public Flux<ProgressEvent> runPipeline(String url) {
        AtomicReference<Path> audioPathRef = new AtomicReference<>();

        return Flux.create(sink -> {
            // Stage 1: Validate URL
            sink.next(ProgressEvent.stage(PipelineStage.VALIDATING_URL, "Validating URL..."));

            UrlValidator.ValidationResult validation = UrlValidator.validate(url);
            if (!validation.valid()) {
                sink.next(ProgressEvent.error("Invalid URL. Please provide a valid YouTube Shorts or Instagram Reels URL."));
                sink.complete();
                return;
            }

            String normalizedUrl = validation.normalizedUrl();
            log.info("Starting pipeline for {} URL: {}", validation.platform(), normalizedUrl);

            // Choose transcript strategy based on platform
            Mono<String> transcriptMono;

            if (validation.platform() == UrlValidator.Platform.YOUTUBE) {
                // YouTube: Try fetching transcript directly (fast, no download needed)
                transcriptMono = getYouTubeTranscript(sink, validation, normalizedUrl, audioPathRef);
            } else {
                // Instagram: Use yt-dlp audio download + Groq Whisper transcription
                transcriptMono = getAudioTranscript(sink, validation, normalizedUrl, audioPathRef);
            }

            transcriptMono
                .flatMap(transcript -> {
                    // Stage 4: Extract Claims
                    sink.next(ProgressEvent.stage(PipelineStage.EXTRACTING_CLAIMS,
                            "Identifying factual claims with AI..."));
                    return claimExtractor.extractClaims(transcript)
                            .map(claims -> Map.entry(transcript, claims));
                })
                .flatMap(entry -> {
                    String transcript = entry.getKey();
                    List<Claim> claims = entry.getValue();

                    if (claims.isEmpty()) {
                        sink.next(ProgressEvent.error("No checkable factual claims found in this video."));
                        sink.complete();
                        return Mono.empty();
                    }

                    // Stage 5: Search Web
                    sink.next(ProgressEvent.stage(PipelineStage.SEARCHING_WEB,
                            "Searching the web for evidence on " + claims.size() + " claims..."));

                    return searchService.searchAllClaims(claims)
                            .map(searchResults -> Map.entry(transcript,
                                    Map.entry(claims, searchResults)));
                })
                .flatMap(outerEntry -> {
                    String transcript = outerEntry.getKey();
                    List<Claim> claims = outerEntry.getValue().getKey();
                    Map<Integer, List<SearchResult>> searchResults = outerEntry.getValue().getValue();

                    // Stage 6: Finalize Report
                    sink.next(ProgressEvent.stage(PipelineStage.FINALIZING_REPORT,
                            "Analyzing evidence and generating verdicts..."));

                    return verdictService.generateVerdicts(claims, searchResults)
                            .map(verdicts -> {
                                double truthScore = FactCheckReport.calculateTruthScore(verdicts);
                                return FactCheckReport.builder()
                                        .url(url)
                                        .transcript(transcript)
                                        .verdicts(verdicts)
                                        .truthScore(truthScore)
                                        .analyzedAt(Instant.now())
                                        .build();
                            });
                })
                .doFinally(signal -> {
                    // Cleanup temp audio file (only exists for Instagram path)
                    Path audioPath = audioPathRef.get();
                    if (audioPath != null) {
                        audioExtractor.cleanup(audioPath).subscribe();
                    }
                })
                .subscribe(
                    report -> {
                        sink.next(ProgressEvent.complete(report));
                        sink.complete();
                    },
                    error -> {
                        log.error("Pipeline error", error);
                        sink.next(ProgressEvent.error(error.getMessage()));
                        sink.complete();
                    }
                );
        });
    }

    /**
     * YouTube path: Fetches transcript directly without downloading video.
     * Falls back to audio download + Groq if no transcript is available.
     */
    private Mono<String> getYouTubeTranscript(
            reactor.core.publisher.FluxSink<ProgressEvent> sink,
            UrlValidator.ValidationResult validation,
            String normalizedUrl,
            AtomicReference<Path> audioPathRef) {

        sink.next(ProgressEvent.stage(PipelineStage.TRANSCRIBING,
                "Fetching YouTube transcript directly..."));

        return youTubeTranscriptService.fetchTranscript(validation.videoId())
                .flatMap(transcript -> {
                    if (transcript != null && !transcript.isBlank()) {
                        log.info("Successfully fetched YouTube transcript directly for video {}", validation.videoId());
                        return Mono.just(transcript);
                    }
                    // Transcript unavailable — fall back to yt-dlp + Groq
                    log.info("No YouTube transcript available for {}, falling back to audio download", validation.videoId());
                    return getAudioTranscript(sink, validation, normalizedUrl, audioPathRef);
                })
                .onErrorResume(e -> {
                    log.warn("YouTube transcript fetch failed for {}: {}, falling back to audio download",
                            validation.videoId(), e.getMessage());
                    return getAudioTranscript(sink, validation, normalizedUrl, audioPathRef);
                });
    }

    /**
     * Instagram/fallback path: Downloads audio via yt-dlp, then transcribes with Groq Whisper.
     */
    private Mono<String> getAudioTranscript(
            reactor.core.publisher.FluxSink<ProgressEvent> sink,
            UrlValidator.ValidationResult validation,
            String normalizedUrl,
            AtomicReference<Path> audioPathRef) {

        sink.next(ProgressEvent.stage(PipelineStage.DOWNLOADING_AUDIO,
                "Downloading audio from " + validation.platform() + "..."));

        return audioExtractor.extractAudio(normalizedUrl)
                .flatMap(audioPath -> {
                    audioPathRef.set(audioPath);

                    sink.next(ProgressEvent.stage(PipelineStage.TRANSCRIBING,
                            "Transcribing audio with Whisper AI..."));
                    return transcriptionService.transcribe(audioPath);
                });
    }
}

