package com.factchecker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AppConfig {

    @Value("${app.groq.api-key}")
    private String groqApiKey;

    @Value("${app.groq.base-url}")
    private String groqBaseUrl;

    @Value("${app.tavily.api-key}")
    private String tavilyApiKey;

    @Value("${app.tavily.base-url}")
    private String tavilyBaseUrl;

    /**
     * WebClient for Groq Whisper API (audio transcription).
     * Configured with 50MB buffer for audio file uploads.
     */
    @Bean
    public WebClient groqWebClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50MB
                .build();

        return WebClient.builder()
                .baseUrl(groqBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + groqApiKey)
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * WebClient for Tavily Search API.
     */
    @Bean
    public WebClient tavilyWebClient() {
        return WebClient.builder()
                .baseUrl(tavilyBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tavilyApiKey)
                .build();
    }
    /**
     * Customizes the RestClient used by Spring AI's OpenAI client.
     * Uses Reactor Netty HttpClient with explicit timeouts to prevent
     * ReadTimeoutException on long-running Gemini API calls.
     */
    @Bean
    public org.springframework.boot.web.client.RestClientCustomizer restClientCustomizer() {
        return builder -> {
            reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                    .responseTimeout(java.time.Duration.ofSeconds(180));

            builder.requestFactory(new org.springframework.http.client.ReactorClientHttpRequestFactory(httpClient));
        };
    }
}

