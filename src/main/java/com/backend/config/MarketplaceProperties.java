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
        credentialEncryptionKey = requiredValue(
                credentialEncryptionKey,
                "app.marketplace.credential-encryption-key");
        byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(credentialEncryptionKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Marketplace credential encryption key must be valid Base64",
                    exception);
        }
        if (decodedKey.length != 32) {
            throw new IllegalArgumentException(
                    "Marketplace credential encryption key must decode to 32 bytes");
        }
        tiktok = requiredProvider(tiktok, "TikTok Shop");
        lazada = requiredProvider(lazada, "Lazada");
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

    private static String requiredValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must be configured");
        }
        return value.trim();
    }

    private static Provider requiredProvider(Provider provider, String providerName) {
        if (provider == null) {
            throw new IllegalArgumentException(providerName + " configuration is incomplete");
        }
        return provider;
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
