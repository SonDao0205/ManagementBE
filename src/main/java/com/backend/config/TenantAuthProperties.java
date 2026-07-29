package com.backend.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record TenantAuthProperties(
        String cookieName,
        Duration sessionDuration,
        boolean cookieSecure,
        String cookieSameSite,
        int maxLoginAttempts,
        Duration lockDuration,
        List<String> allowedOriginPatterns) {

    public TenantAuthProperties {
        cookieName = valueOrDefault(cookieName, "omni_tenant_session");
        sessionDuration = sessionDuration == null ? Duration.ofHours(8) : sessionDuration;
        cookieSameSite = valueOrDefault(cookieSameSite, "Lax");
        maxLoginAttempts = maxLoginAttempts <= 0 ? 5 : maxLoginAttempts;
        lockDuration = lockDuration == null ? Duration.ofMinutes(15) : lockDuration;
        allowedOriginPatterns = allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()
                ? List.of("http://localhost:*", "http://127.0.0.1:*")
                : List.copyOf(allowedOriginPatterns);
        if (allowedOriginPatterns.contains("*")) {
            throw new IllegalArgumentException("CORS origin patterns must not allow every origin");
        }
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
