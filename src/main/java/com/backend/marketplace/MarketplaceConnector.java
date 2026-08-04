package com.backend.marketplace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface MarketplaceConnector {

    String marketplaceCode();

    String authorizationUrl(String state, String redirectUri);

    TokenResult exchangeAuthorizationCode(String code, String redirectUri);

    TokenResult refresh(String refreshToken);

    ShopProfile getShopProfile(String accessToken);

    List<Map<String, Object>> getProducts(String accessToken);

    List<Map<String, Object>> getOrders(String accessToken);

    ProductPublishResult publishProduct(
            String accessToken,
            ProductPublishRequest product);

    void revoke(String token);

    record TokenResult(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt,
            List<String> scopes) {
    }

    record ShopProfile(
            String externalAccountId,
            String shopCipher,
            String shopName,
            String siteId,
            String currency,
            String timezoneName) {
    }

    record ProductPublishRequest(
            String externalProductId,
            String title,
            String description,
            List<ProductVariantPublishRequest> variants) {
    }

    record ProductVariantPublishRequest(
            String productVariantId,
            String variantName,
            String sellerSku,
            String color,
            String size,
            java.math.BigDecimal price,
            int quantity) {
    }

    record ProductPublishResult(
            String productId,
            List<ProductVariantPublishResult> variants) {
    }

    record ProductVariantPublishResult(
            String productVariantId,
            String skuId,
            String sellerSku) {
    }
}
