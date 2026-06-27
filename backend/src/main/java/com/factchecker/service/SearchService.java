package com.factchecker.service;

import com.factchecker.exception.FactCheckException;
import com.factchecker.model.Claim;
import com.factchecker.model.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Searches the web for evidence to verify claims using the Tavily Search API.
 * Each claim is searched independently, and results are collected per claim.
 */
@Slf4j
@Service
public class SearchService {

    private final WebClient tavilyWebClient;

    public SearchService(@Qualifier("tavilyWebClient") WebClient tavilyWebClient) {
        this.tavilyWebClient = tavilyWebClient;
    }

    /**
     * Searches for evidence for all claims in parallel.
     * Returns a list of search results grouped by claim ID.
     */
    public Mono<Map<Integer, List<SearchResult>>> searchAllClaims(List<Claim> claims) {
        log.info("Searching web for {} claims", claims.size());

        return Flux.fromIterable(claims)
                .flatMap(claim ->
                        searchClaim(claim)
                                .map(results -> Map.entry(claim.getId(), results))
                                .onErrorResume(e -> {
                                    log.warn("Search failed for claim {}: {}", claim.getId(), e.getMessage());
                                    return Mono.just(Map.entry(claim.getId(), List.<SearchResult>of()));
                                }),
                        3 // concurrency limit
                )
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(this::applyTokenBudget);
    }

    /**
     * Enforces a global character limit across all search results to stay within
     * the 20,000 token budget (~80,000 characters) for the synthesis request.
     */
    private Map<Integer, List<SearchResult>> applyTokenBudget(Map<Integer, List<SearchResult>> resultsMap) {
        int totalChars = 0;
        int maxChars = 20000; // Reduced from 60k to 20k to significantly speed up Gemini processing time

        Map<Integer, List<SearchResult>> budgetedMap = new java.util.HashMap<>();
        for (Map.Entry<Integer, List<SearchResult>> entry : resultsMap.entrySet()) {
            List<SearchResult> budgetedResults = new ArrayList<>();
            for (SearchResult r : entry.getValue()) {
                String content = r.getContent() != null ? r.getContent() : "";
                
                if (totalChars >= maxChars) {
                    continue; // Global budget exhausted
                }

                if (totalChars + content.length() > maxChars) {
                    int allowed = maxChars - totalChars;
                    content = content.substring(0, allowed) + "...";
                }

                totalChars += content.length();

                budgetedResults.add(SearchResult.builder()
                        .title(r.getTitle())
                        .url(r.getUrl())
                        .content(content)
                        .score(r.getScore())
                        .build());
            }
            budgetedMap.put(entry.getKey(), budgetedResults);
        }
        
        log.debug("Token budget applied. Total content chars: {}", totalChars);
        return budgetedMap;
    }

    /**
     * Searches for evidence for a single claim using Tavily.
     */
    private Mono<List<SearchResult>> searchClaim(Claim claim) {
        log.debug("Searching for claim {}: {}", claim.getId(), claim.getClaim());

        Map<String, Object> requestBody = Map.of(
                "query", "Fact check: " + claim.getClaim(),
                "search_depth", "basic",
                "include_answer", false,
                "include_raw_content", false,
                "max_results", 5
        );

        return tavilyWebClient.post()
                .uri("/search")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("Tavily API error ({}): {}", response.statusCode(), body);
                                    return Mono.error(new FactCheckException(
                                            "Web search failed: " + response.statusCode()));
                                })
                )
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    List<SearchResult> results = new ArrayList<>();
                    JsonNode resultsNode = json.path("results");

                    if (resultsNode.isArray()) {
                        for (JsonNode result : resultsNode) {
                            results.add(SearchResult.builder()
                                    .title(result.path("title").asText(""))
                                    .url(result.path("url").asText(""))
                                    .content(result.path("content").asText(""))
                                    .score(result.path("score").asDouble(0.0))
                                    .build());
                        }
                    }

                    log.debug("Found {} results for claim {}", results.size(), claim.getId());
                    return results;
                });
    }
}
