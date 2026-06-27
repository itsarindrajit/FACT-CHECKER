package com.factchecker.controller;

import com.factchecker.model.FactCheckRequest;
import com.factchecker.model.ProgressEvent;
import com.factchecker.service.FactCheckOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FactCheckController {

    private final FactCheckOrchestrator orchestrator;

    // Simple in-memory rate limiter: IP -> request timestamps
    private final Map<String, AtomicInteger> rateLimitMap = new ConcurrentHashMap<>();

    /**
     * Main fact-check endpoint. Returns an SSE stream of progress events.
     * The client receives real-time updates as the pipeline progresses.
     */
    @PostMapping(value = "/fact-check", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ProgressEvent>> factCheck(
            @Valid @RequestBody FactCheckRequest request,
            @RequestHeader(value = "X-Forwarded-For", defaultValue = "unknown") String clientIp) {

        log.info("Fact-check request from {}: {}", clientIp, request.getUrl());

        // Simple rate limiting
        AtomicInteger counter = rateLimitMap.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() > 5) {
            return Flux.just(ServerSentEvent.<ProgressEvent>builder()
                    .event("error")
                    .data(ProgressEvent.error("Rate limit exceeded. Please wait a minute before trying again."))
                    .build());
        }

        // Reset counter after 1 minute
        Flux.just(clientIp)
                .delayElements(Duration.ofMinutes(1))
                .doOnNext(ip -> {
                    AtomicInteger c = rateLimitMap.get(ip);
                    if (c != null) c.decrementAndGet();
                })
                .subscribe();

        return orchestrator.runPipeline(request.getUrl())
                .map(event -> ServerSentEvent.<ProgressEvent>builder()
                        .event(event.getStage().name().toLowerCase())
                        .data(event)
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<ProgressEvent>builder()
                        .comment("stream-end")
                        .build()));
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "reel-fact-checker");
    }
}
