package com.factchecker.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Prevents Render free-tier from spinning down the container after 15 minutes of inactivity.
 * Sends a self-ping to the app's own health endpoint every 10 minutes.
 *
 * The RENDER_EXTERNAL_URL environment variable is automatically provided by Render
 * and contains the public URL of the service (e.g., https://your-app.onrender.com).
 * We ping via the external URL so Render's load balancer sees it as real inbound traffic.
 */
@Slf4j
@Component
public class KeepAliveScheduler {

    private final WebClient webClient;
    private final String pingUrl;
    private final boolean enabled;

    public KeepAliveScheduler(
            @Value("${RENDER_EXTERNAL_URL:}") String renderExternalUrl,
            @Value("${app.keep-alive.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.webClient = WebClient.builder().build();

        if (renderExternalUrl != null && !renderExternalUrl.isBlank()) {
            // Use the external URL so Render's ingress counts it as traffic
            this.pingUrl = renderExternalUrl + "/api/health";
        } else {
            // Fallback for local development — won't prevent Render shutdown
            // but avoids errors when running locally
            this.pingUrl = "http://localhost:8080/api/health";
        }

        if (enabled) {
            log.info("Keep-alive scheduler enabled. Ping URL: {}", this.pingUrl);
        } else {
            log.info("Keep-alive scheduler disabled.");
        }
    }

    /**
     * Pings the health endpoint every 10 minutes.
     * Render free tier shuts down after 15 minutes of inactivity,
     * so 10 minutes gives comfortable headroom.
     */
    @Scheduled(fixedRate = 600_000, initialDelay = 600_000) // 10 minutes
    public void keepAlive() {
        if (!enabled) return;

        webClient.get()
                .uri(pingUrl)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(body -> log.debug("Keep-alive ping successful: {}", pingUrl))
                .doOnError(error -> log.warn("Keep-alive ping failed: {}", error.getMessage()))
                .subscribe();
    }
}
