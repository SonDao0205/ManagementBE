package com.backend.config;

import java.time.Duration;
import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.marketplace")
public record MarketplaceProperties(
        String callbackBaseUrl,
        Duration authorizationTtl,
        String credentialEncryptionKey,
        Provider tiktok,
        Provider lazada) {

    public MarketplaceProperties {
        callbackBaseUrl = defaultValue(callbackBaseUrl, "http://localhost:8081");
        authorizationTtl = authorizationTtl == null
                ? Duration.ofMinutes(10)
                : authorizationTtl;
        credentialEncryptionKey = defaultValue(
                credentialEncryptionKey,
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        if (Base64.getDecoder().decode(credentialEncryptionKey).length != 32) {
            throw new IllegalArgumentException(
                    "Marketplace credential encryption key must decode to 32 bytes");
        }
        tiktok = tiktok == null
                ? new Provider(
                        "http://localhost:4011",
                        "omni-tiktok-local",
                        "tiktok-local-secret-change-me")
                : tiktok;
        lazada = lazada == null
                ? new Provider(
                        "http://localhost:4012",
                        "omni-lazada-local",
                        "lazada-local-secret-change-me")
                : lazada;
    }

    public Provider provider(String marketplaceCode) {
        return switch (marketplaceCode) {
            case "TIKTOK_SHOP" -> tiktok;
            case "LAZADA" -> lazada;
            default -> throw new IllegalArgumentException(
                    "Unsupported marketplace: " + marketplaceCode);
        };
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Provider(String baseUrl, String clientId, String clientSecret) {

        public Provider {
            if (baseUrl == null || clientId == null || clientSecret == null
                    || baseUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
                throw new IllegalArgumentException(
                        "Marketplace provider configuration is incomplete");
            }
        }
    }
}
