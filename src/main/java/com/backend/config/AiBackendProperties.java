package com.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiBackendProperties(String baseUrl, String serviceToken) {
}
