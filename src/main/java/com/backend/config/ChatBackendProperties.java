package com.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat-backend")
public record ChatBackendProperties(String baseUrl, String serviceToken) {
}
