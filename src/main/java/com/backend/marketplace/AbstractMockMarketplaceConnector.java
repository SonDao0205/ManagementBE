package com.backend.marketplace;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
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
    public List<Map<String, Object>> getProducts(String accessToken) {
        return marketplaceRows("/mock/seller/products", accessToken, "sản phẩm");
    }

    @Override
    public List<Map<String, Object>> getOrders(String accessToken) {
        return marketplaceRows("/mock/seller/orders", accessToken, "đơn hàng");
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProductPublishResult publishProduct(
            String accessToken,
            ProductPublishRequest product) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("external_product_id", product.externalProductId());
            body.put("title", product.title());
            body.put("description", product.description() == null ? "" : product.description());
            body.put("variants", product.variants().stream().map(variant -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("external_variant_id", variant.productVariantId());
                item.put("variant_name", variant.variantName());
                item.put("seller_sku", variant.sellerSku());
                item.put("color", variant.color());
                item.put("size", variant.size());
                item.put("price", variant.price());
                item.put("quantity", variant.quantity());
                return item;
            }).toList());
            Map<String, Object> response = data(restClient.post()
                    .uri("/mock/seller/products")
                    .header("Authorization", "Bearer " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(Map.class));
            Object rawVariants = response.get("variants");
            List<ProductVariantPublishResult> variants = rawVariants instanceof List<?> rows
                    ? rows.stream()
                            .filter(Map.class::isInstance)
                            .map(Map.class::cast)
                            .map(row -> new ProductVariantPublishResult(
                                    text(row, "external_variant_id"),
                                    text(row, "sku_id"),
                                    text(row, "seller_sku")))
                            .toList()
                    : List.of();
            return new ProductPublishResult(text(response, "product_id"), variants);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable("Không thể đăng sản phẩm lên sàn giả lập.", exception);
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
    private List<Map<String, Object>> marketplaceRows(
            String path,
            String accessToken,
            String resourceName) {
        try {
            Map<?, ?> wrapper = restClient.get()
                    .uri(path)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);
            if (wrapper == null || !(wrapper.get("data") instanceof List<?> rows)) {
                throw new IllegalArgumentException("Marketplace response has no data list");
            }
            return rows.stream()
                    .filter(Map.class::isInstance)
                    .map(value -> (Map<String, Object>) value)
                    .toList();
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable("Không thể tải " + resourceName + " từ sàn giả lập.", exception);
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
