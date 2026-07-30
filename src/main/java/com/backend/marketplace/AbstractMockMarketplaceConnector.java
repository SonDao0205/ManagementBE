package com.backend.marketplace;

import java.net.http.HttpClient;
import java.math.BigDecimal;
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
    public ProductResult upsertProduct(String accessToken, ProductPayload product) {
        try {
            Map<String, Object> data = data(restClient.post()
                    .uri("/mock/seller/products")
                    .header("Authorization", "Bearer " + accessToken)
                    .body(Map.of(
                            "external_product_id", product.id(),
                            "product_code", product.productCode(),
                            "name", product.name(),
                            "description", valueOrEmpty(product.description()),
                            "category", valueOrEmpty(product.category()),
                            "status", product.status(),
                            "image_url", valueOrEmpty(product.imageUrl()),
                            "variants", product.variants().stream()
                                    .map(variant -> Map.of(
                                            "external_variant_id", variant.id(),
                                            "sku", variant.sku(),
                                            "name", valueOrEmpty(variant.name()),
                                            "price", variant.price(),
                                            "stock", variant.stock()))
                                    .toList()))
                    .retrieve()
                    .body(Map.class));
            return new ProductResult(
                    text(data, "external_product_id"),
                    textOr(data, "status", "ACTIVE"),
                    String.valueOf(data.getOrDefault("version", "1")),
                    productVariantResults(data.get("variants")));
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable(
                    "Không thể đồng bộ sản phẩm lên " + marketplaceCode + ".",
                    exception);
        }
    }

    @Override
    public List<MarketplaceProductPayload> getProducts(String accessToken) {
        try {
            Map<String, Object> data = data(restClient.get()
                    .uri("/mock/seller/products")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class));
            return marketplaceProductPayloads(data.get("products"));
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable(
                    "Không thể đồng bộ sản phẩm từ " + marketplaceCode + ".",
                    exception);
        }
    }

    @Override
    public List<OrderPayload> getOrders(String accessToken) {
        try {
            Map<String, Object> data = data(restClient.get()
                    .uri("/mock/seller/orders")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class));
            return orderPayloads(data.get("orders"));
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable(
                    "Không thể đồng bộ đơn hàng từ " + marketplaceCode + ".",
                    exception);
        }
    }

    @Override
    public OrderPayload updateOrderStatus(
            String accessToken,
            String externalOrderId,
            String canonicalStatus) {
        try {
            Map<String, Object> data = data(restClient.put()
                    .uri("/mock/seller/orders/status")
                    .header("Authorization", "Bearer " + accessToken)
                    .body(Map.of(
                            "order_id", externalOrderId,
                            "canonical_status", canonicalStatus))
                    .retrieve()
                    .body(Map.class));
            return orderPayload(data);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable(
                    "Không thể cập nhật trạng thái đơn hàng trên " + marketplaceCode + ".",
                    exception);
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

    private static List<ProductVariantResult> productVariantResults(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> variant = (Map<String, Object>) item;
                    return new ProductVariantResult(
                            text(variant, "product_variant_id"),
                            text(variant, "external_sku_id"),
                            text(variant, "seller_sku"),
                            decimal(variant, "price"),
                            integer(variant, "stock"),
                            textOr(variant, "status", "ACTIVE"));
                })
                .toList();
    }

    private static List<MarketplaceProductPayload> marketplaceProductPayloads(
            Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> product = (Map<String, Object>) item;
                    return new MarketplaceProductPayload(
                            text(product, "external_product_id"),
                            textOr(
                                    product,
                                    "product_code",
                                    text(product, "external_product_id")),
                            text(product, "name"),
                            textOr(product, "description", ""),
                            textOr(product, "category", ""),
                            textOr(product, "status", "ACTIVE"),
                            String.valueOf(product.getOrDefault("version", "1")),
                            textOr(product, "image_url", ""),
                            marketplaceProductVariantPayloads(
                                    product.get("variants")));
                })
                .toList();
    }

    private static List<MarketplaceProductVariantPayload>
            marketplaceProductVariantPayloads(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> variant = (Map<String, Object>) item;
                    return new MarketplaceProductVariantPayload(
                            text(variant, "external_sku_id"),
                            text(variant, "seller_sku"),
                            textOr(variant, "name", ""),
                            decimal(variant, "price"),
                            integer(variant, "stock"),
                            textOr(variant, "status", "ACTIVE"));
                })
                .toList();
    }

    private static List<OrderPayload> orderPayloads(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> order = (Map<String, Object>) item;
                    return orderPayload(order);
                })
                .toList();
    }

    private static OrderPayload orderPayload(Map<String, Object> order) {
        return new OrderPayload(
                text(order, "external_order_id"),
                text(order, "raw_status"),
                text(order, "canonical_status"),
                textOr(order, "payment_status", "UNPAID"),
                textOr(order, "currency", "VND"),
                decimalOrZero(order, "subtotal_amount"),
                decimalOrZero(order, "shipping_amount"),
                decimalOrZero(order, "discount_amount"),
                decimalOrZero(order, "total_amount"),
                textOr(order, "customer_name", "Khách hàng"),
                textOr(order, "customer_phone", ""),
                mapValue(order.get("shipping_address")),
                nullableText(order, "external_package_id"),
                nullableText(order, "tracking_number"),
                nullableText(order, "shipping_provider"),
                instant(order, "created_at"),
                instant(order, "updated_at"),
                orderItemPayloads(order.get("items")));
    }

    private static List<OrderItemPayload> orderItemPayloads(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> orderItem = (Map<String, Object>) item;
                    return new OrderItemPayload(
                            text(orderItem, "external_order_item_id"),
                            textOr(orderItem, "external_product_id", ""),
                            nullableText(orderItem, "external_sku_id"),
                            nullableText(orderItem, "seller_sku"),
                            text(orderItem, "product_name"),
                            nullableText(orderItem, "variant_name"),
                            integer(orderItem, "quantity"),
                            decimalOrZero(orderItem, "unit_price"),
                            decimalOrZero(orderItem, "discount_amount"),
                            decimalOrZero(orderItem, "paid_amount"));
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
    }

    private static Instant instant(Map<String, Object> data, String key) {
        String value = text(data, key);
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Marketplace date is invalid: " + key, exception);
        }
    }

    private static BigDecimal decimalOrZero(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new IllegalArgumentException("Marketplace number is invalid: " + key);
    }

    private static BigDecimal decimal(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new IllegalArgumentException("Marketplace number is missing: " + key);
    }

    private static int integer(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Marketplace number is missing: " + key);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static AuthenticationException unavailable(String message, Exception cause) {
        return new AuthenticationException(
                HttpStatus.BAD_GATEWAY,
                "MARKETPLACE_UNAVAILABLE",
                message,
                cause);
    }
}
