package com.backend.marketplace;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.backend.config.MarketplaceProperties;
import com.backend.service.AuthenticationException;

abstract class AbstractMockMarketplaceConnector implements MarketplaceConnector {

    private final String marketplaceCode;
    private final MarketplaceProperties.Provider provider;
    private final RestClient restClient;

    protected AbstractMockMarketplaceConnector(
            String marketplaceCode,
            MarketplaceProperties.Provider provider) {
        this.marketplaceCode = marketplaceCode;
        this.provider = provider;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = RestClient.builder()
                .baseUrl(provider.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String marketplaceCode() {
        return marketplaceCode;
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        return UriComponentsBuilder
                .fromUriString(provider.baseUrl() + "/mock/oauth/authorize")
                .queryParam("client_id", provider.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    @Override
    public TokenResult exchangeAuthorizationCode(String code, String redirectUri) {
        return tokenRequest(Map.of(
                "grant_type", "authorization_code",
                "client_id", provider.clientId(),
                "client_secret", provider.clientSecret(),
                "code", code,
                "redirect_uri", redirectUri));
    }

    @Override
    public TokenResult refresh(String refreshToken) {
        return tokenRequest(Map.of(
                "grant_type", "refresh_token",
                "client_id", provider.clientId(),
                "client_secret", provider.clientSecret(),
                "refresh_token", refreshToken));
    }

    @Override
    public ShopProfile getShopProfile(String accessToken) {
        try {
            Map<String, Object> data = data(restClient.get()
                    .uri("/mock/oauth/shop")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class));
            return new ShopProfile(
                    text(data, "external_account_id"),
                    nullableText(data, "shop_cipher"),
                    text(data, "shop_name"),
                    textOr(data, "site_id", "VN"),
                    textOr(data, "currency", "VND"),
                    textOr(data, "timezone_name", "Asia/Ho_Chi_Minh"));
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable("Không thể xác minh thông tin shop từ sàn giả lập.", exception);
        }
    }

    @Override
    public void revoke(String token) {
        try {
            restClient.post()
                    .uri("/mock/oauth/revoke")
                    .body(Map.of(
                            "client_id", provider.clientId(),
                            "client_secret", provider.clientSecret(),
                            "token", token))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw unavailable("Không thể thu hồi token tại sàn giả lập.", exception);
        }
    }

    private TokenResult tokenRequest(Map<String, Object> request) {
        try {
            Map<String, Object> data = data(restClient.post()
                    .uri("/mock/oauth/token")
                    .body(request)
                    .retrieve()
                    .body(Map.class));
            Instant now = Instant.now();
            long expiresIn = number(data, "expires_in");
            long refreshExpiresIn = number(data, "refresh_expires_in");
            return new TokenResult(
                    text(data, "access_token"),
                    text(data, "refresh_token"),
                    now.plusSeconds(expiresIn),
                    now.plusSeconds(refreshExpiresIn),
                    stringList(data.get("scopes")));
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable("Không thể đổi hoặc làm mới token từ sàn giả lập.", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<?, ?> wrapper) {
        if (wrapper == null || !(wrapper.get("data") instanceof Map<?, ?> value)) {
            throw new IllegalArgumentException("Marketplace response has no data");
        }
        return (Map<String, Object>) value;
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Marketplace field is missing: " + key);
        }
        return text;
    }

    private static String nullableText(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String textOr(Map<String, Object> data, String key, String fallback) {
        String value = nullableText(data, key);
        return value == null ? fallback : value;
    }

    private static long number(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalArgumentException("Marketplace number is missing: " + key);
        }
        return number.longValue();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static AuthenticationException unavailable(String message, Exception cause) {
        return new AuthenticationException(
                HttpStatus.BAD_GATEWAY,
                "MARKETPLACE_UNAVAILABLE",
                message,
                cause);
    }
}
