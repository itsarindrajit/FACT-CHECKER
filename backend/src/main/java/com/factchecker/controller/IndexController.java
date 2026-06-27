package com.factchecker.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class IndexController {

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of(
            "status", "running",
            "message", "Fact Checker Backend is active! Access api endpoints under /api",
            "health", "/api/health"
        );
    }
}
