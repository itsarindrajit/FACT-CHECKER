package com.factchecker.service;

import com.factchecker.exception.FactCheckException;
import com.factchecker.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VerdictService {

    private final ChatClient chatClient;
    private final BeanOutputConverter<List<VerdictResult>> outputConverter;

    private static final String SYSTEM_PROMPT = """
            You are a rigorous fact-checking judge. For each claim, compare it against the provided web search evidence.
            
            Verdict must be: "TRUE", "FALSE", or "MISLEADING".
            - TRUE = substantially correct based on evidence
            - FALSE = factually incorrect based on evidence
            - MISLEADING = some truth but deceptive/lacks context
            - If evidence is insufficient, use MISLEADING with lower confidence
            """;

    public VerdictService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.outputConverter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<VerdictResult>>() {});
    }

    public Mono<List<ClaimVerdict>> generateVerdicts(
            List<Claim> claims, Map<Integer, List<SearchResult>> searchResults) {

        log.info("Generating verdicts for {} claims", claims.size());

        return Mono.fromCallable(() -> {
            String userPrompt = buildUserPrompt(claims, searchResults);

            String response = chatClient.prompt()
                    .system(sys -> sys.text(SYSTEM_PROMPT + "\n\n{format}").param("format", outputConverter.getFormat()))
                    .user(userPrompt)
                    .call()
                    .content();

            log.debug("Gemini verdict response: {}", response);

            try {
                List<VerdictResult> verdictResults = outputConverter.convert(response);
                return mergeVerdicts(claims, verdictResults);
            } catch (Exception e) {
                log.error("Failed to parse verdicts JSON: {}", response, e);
                throw new FactCheckException("Failed to parse AI verdict response: " + e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildUserPrompt(List<Claim> claims, Map<Integer, List<SearchResult>> searchResults) {
        StringBuilder sb = new StringBuilder("Analyze these claims against evidence:\n\n");
        for (Claim claim : claims) {
            sb.append("--- CLAIM ").append(claim.getId()).append(" ---\n");
            sb.append("Statement: ").append(claim.getClaim()).append("\n");
            sb.append("Category: ").append(claim.getCategory()).append("\nEvidence:\n");
            List<SearchResult> results = searchResults.getOrDefault(claim.getId(), List.of());
            if (results.isEmpty()) {
                sb.append("  (No results found)\n");
            } else {
                for (int i = 0; i < results.size(); i++) {
                    SearchResult r = results.get(i);
                    sb.append("  ").append(i + 1).append(". ").append(r.getTitle())
                      .append("\n  URL: ").append(r.getUrl())
                      .append("\n  ").append(truncate(r.getContent(), 300)).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    private List<ClaimVerdict> mergeVerdicts(List<Claim> claims, List<VerdictResult> verdictResults) {
        Map<Integer, Claim> claimMap = claims.stream()
                .collect(Collectors.toMap(Claim::getId, c -> c));
        List<ClaimVerdict> merged = new ArrayList<>();
        for (VerdictResult vr : verdictResults) {
            Claim claim = claimMap.get(vr.claimId);
            if (claim != null) {
                merged.add(ClaimVerdict.builder()
                        .claimId(vr.claimId).claim(claim.getClaim()).category(claim.getCategory())
                        .verdict(Verdict.valueOf(vr.verdict.toUpperCase()))
                        .explanation(vr.explanation).sourceUrl(vr.sourceUrl)
                        .confidence(vr.confidence).build());
            }
        }
        return merged;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private record VerdictResult(int claimId, String verdict, String explanation,
                                  String sourceUrl, double confidence) {}
}
