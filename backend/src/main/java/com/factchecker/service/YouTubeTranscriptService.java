package com.factchecker.service;

import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptList;
import io.github.thoroldvix.api.YoutubeClient;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import io.github.thoroldvix.api.TranscriptRetrievalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches YouTube video transcripts (auto-generated captions) directly via HTTP.
 * Supports cookie-based authentication to bypass datacenter IP blocks on Render.
 *
 * Falls back gracefully if no transcript is available.
 */
@Slf4j
@Service
public class YouTubeTranscriptService {

    @Value("${app.ytdlp.cookies-path:}")
    private String cookiesPath;

    private YoutubeTranscriptApi transcriptApi;

    @PostConstruct
    public void init() {
        if (cookiesPath != null && !cookiesPath.isBlank() && Files.exists(Paths.get(cookiesPath))) {
            log.info("Initializing YouTube Transcript API with cookies from: {}", cookiesPath);
            try {
                CookieManager cookieManager = parseCookiesTxt(Paths.get(cookiesPath));
                HttpClient httpClient = HttpClient.newBuilder()
                        .cookieHandler(cookieManager)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                YoutubeClient cookieClient = new CookieYoutubeClient(httpClient);
                this.transcriptApi = TranscriptApiFactory.createWithClient(cookieClient);
                log.info("YouTube Transcript API initialized with cookie authentication");
            } catch (Exception e) {
                log.warn("Failed to parse cookies file, falling back to unauthenticated: {}", e.getMessage());
                this.transcriptApi = TranscriptApiFactory.createDefault();
            }
        } else {
            log.info("No cookies file found, using unauthenticated YouTube Transcript API");
            this.transcriptApi = TranscriptApiFactory.createDefault();
        }
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

    /**
     * Parses a Netscape-format cookies.txt file into a CookieManager.
     */
    private CookieManager parseCookiesTxt(Path cookiesFile) throws IOException {
        CookieManager cookieManager = new CookieManager();
        for (String line : Files.readAllLines(cookiesFile)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\t");
            if (parts.length < 7) continue;

            String domain = parts[0];
            String path = parts[2];
            boolean secure = "TRUE".equalsIgnoreCase(parts[3]);
            String name = parts[5];
            String value = parts[6];

            HttpCookie cookie = new HttpCookie(name, value);
            cookie.setDomain(domain.startsWith(".") ? domain : "." + domain);
            cookie.setPath(path);
            cookie.setSecure(secure);
            cookie.setVersion(0);

            String scheme = secure ? "https" : "http";
            String cleanDomain = domain.startsWith(".") ? domain.substring(1) : domain;
            URI uri = URI.create(scheme + "://" + cleanDomain + path);
            cookieManager.getCookieStore().add(uri, cookie);
        }
        log.info("Parsed {} cookies from {}", cookieManager.getCookieStore().getCookies().size(), cookiesFile);
        return cookieManager;
    }

    /**
     * Custom YoutubeClient that uses an HttpClient with cookie support.
     */
    private static class CookieYoutubeClient implements YoutubeClient {
        private final HttpClient httpClient;

        CookieYoutubeClient(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public String get(String url, Map<String, String> headers) throws TranscriptRetrievalException {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET();
                if (headers != null) {
                    headers.forEach(builder::header);
                }
                HttpResponse<String> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new TranscriptRetrievalException(url,
                            "Request to YouTube failed. Status code: " + response.statusCode());
                }
                return response.body();
            } catch (TranscriptRetrievalException e) {
                throw e;
            } catch (Exception e) {
                throw new TranscriptRetrievalException(url, "HTTP request failed: " + e.getMessage());
            }
        }

        @Override
        public String post(String url, String body) throws TranscriptRetrievalException {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new TranscriptRetrievalException(url,
                            "Request to YouTube failed. Status code: " + response.statusCode());
                }
                return response.body();
            } catch (TranscriptRetrievalException e) {
                throw e;
            } catch (Exception e) {
                throw new TranscriptRetrievalException(url, "HTTP request failed: " + e.getMessage());
            }
        }
    }
}
