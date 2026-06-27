package com.factchecker.service;

import com.factchecker.exception.FactCheckException;
import com.factchecker.model.Claim;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Uses Gemini 1.5 Flash (via Spring AI) to extract 3-5 specific, checkable
 * factual claims from a video transcript.
 */
@Slf4j
@Service
public class ClaimExtractorService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are a precision fact-checking analyst. Given a video transcript, extract 3 to 5 specific,
            checkable factual claims. Focus on:
            - Statistical claims (numbers, percentages, dates , timelines)
            - Historical or scientific assertions
            - Claims about specific people, organizations, or events
            - Behavioral or psychological claims about humans or animals (e.g., "Dogs perceive humans as members of their own species").
            - Cause-and-effect statements(e.g., "X happens because of Y").

            Rules for Extraction:
            - Capture the core factual "seed" of an assertion even if phrased informally.
            - Do NOT extract pure opinions (e.g., "I think dogs are great").
            - Do NOT extract subjective feelings (e.g., "Monkeys feel scary").
            - Extract claims that can be verified or debunked using scientific research, news archives, or expert consensus.
            -If the transcript contains fewer than 3 checkable claims, extract as many as you can find.

            Respond ONLY with a valid JSON array. No markdown, no code fences, no explanation.
            Each element must have:
            - "id": sequential integer starting at 1
            - "claim": the exact factual claim as a clear, standalone sentence
            - "category": one of ["STATISTIC", "HISTORICAL", "SCIENTIFIC", "ATTRIBUTION", "CAUSATION"]

            Example output:
            [
              {"id": 1, "claim": "The Earth's population reached 8 billion in November 2022.", "category": "STATISTIC"},
              {"id": 2, "claim": "Albert Einstein published his theory of general relativity in 1915.", "category": "HISTORICAL"}
            ]
            """;

    public ClaimExtractorService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts factual claims from the transcript using Gemini.
     */
    public Mono<List<Claim>> extractClaims(String transcript) {
        log.info("Extracting claims from transcript ({} chars)", transcript.length());

        return Mono.fromCallable(() -> {
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("Extract factual claims from this video transcript:\n\n" + transcript)
                    .call()
                    .content();

            log.debug("Gemini claim extraction response: {}", response);

            // Clean response - remove markdown code fences if present
            String cleaned = cleanJsonResponse(response);

            try {
                List<Claim> claims = objectMapper.readValue(cleaned, new TypeReference<>() {
                });
                log.info("Extracted {} claims", claims.size());
                return claims;
            } catch (Exception e) {
                log.error("Failed to parse claims JSON: {}", cleaned, e);
                throw new FactCheckException("Failed to parse AI response for claims: " + e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Strips markdown code fences and extra whitespace from JSON responses.
     */
    private String cleanJsonResponse(String response) {
        if (response == null)
            return "[]";
        String cleaned = response.trim();
        // Remove ```json ... ``` wrapper
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return cleaned;
    }
}
