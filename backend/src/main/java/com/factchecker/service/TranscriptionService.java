package com.factchecker.service;

import com.factchecker.exception.FactCheckException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/**
 * Sends audio files to the Groq Whisper API for speech-to-text transcription.
 * Uses the free whisper-large-v3 model.
 */
@Slf4j
@Service
public class TranscriptionService {

    private final WebClient groqWebClient;

    public TranscriptionService(@Qualifier("groqWebClient") WebClient groqWebClient) {
        this.groqWebClient = groqWebClient;
    }

    /**
     * Transcribes the audio file at the given path using Groq Whisper API.
     * Returns the raw transcript text.
     */
    public Mono<String> transcribe(Path audioFilePath) {
        log.info("Transcribing audio file: {}", audioFilePath);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new FileSystemResource(audioFilePath.toFile()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        bodyBuilder.part("model", "whisper-large-v3");
        bodyBuilder.part("response_format", "json");
        bodyBuilder.part("language", "en");

        return groqWebClient.post()
                .uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("Groq API error ({}): {}", response.statusCode(), body);
                                    return Mono.error(new FactCheckException(
                                            "Transcription failed: " + response.statusCode() + " - " + body));
                                })
                )
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    String text = json.path("text").asText("");
                    if (text.isBlank()) {
                        throw new FactCheckException("Transcription returned empty text");
                    }
                    log.info("Transcription complete: {} characters", text.length());
                    log.debug("Transcript: {}", text.substring(0, Math.min(200, text.length())));
                    return text;
                });
    }
}
