package com.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.staff.mail")
public record StaffMailProperties(String from, String loginUrl) {
    public StaffMailProperties {
        from = from == null ? "" : from.trim();
        loginUrl = loginUrl == null || loginUrl.isBlank()
                ? "http://localhost:5173/login"
                : loginUrl.trim();
    }
}
