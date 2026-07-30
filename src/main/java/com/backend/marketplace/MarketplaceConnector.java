package com.backend.marketplace;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface MarketplaceConnector {

    String marketplaceCode();

    String authorizationUrl(String state, String redirectUri);

    TokenResult exchangeAuthorizationCode(String code, String redirectUri);

    TokenResult refresh(String refreshToken);

    ShopProfile getShopProfile(String accessToken);

    List<MarketplaceProductPayload> getProducts(String accessToken);

    ProductResult upsertProduct(String accessToken, ProductPayload product);

    List<OrderPayload> getOrders(String accessToken);

    OrderPayload updateOrderStatus(
            String accessToken,
            String externalOrderId,
            String canonicalStatus);

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

    record ProductPayload(
            String id,
            String productCode,
            String name,
            String description,
            String category,
            String status,
            String imageUrl,
            List<ProductVariantPayload> variants) {
    }

    record ProductVariantPayload(
            String id,
            String sku,
            String name,
            BigDecimal price,
            int stock) {
    }

    record MarketplaceProductPayload(
            String externalProductId,
            String productCode,
            String name,
            String description,
            String category,
            String status,
            String version,
            String imageUrl,
            List<MarketplaceProductVariantPayload> variants) {
    }

    record MarketplaceProductVariantPayload(
            String externalSkuId,
            String sellerSku,
            String name,
            BigDecimal price,
            int stock,
            String status) {
    }

    record ProductResult(
            String externalProductId,
            String status,
            String version,
            List<ProductVariantResult> variants) {
    }

    record ProductVariantResult(
            String productVariantId,
            String externalSkuId,
            String sellerSku,
            BigDecimal price,
            int stock,
            String status) {
    }

    record OrderPayload(
            String externalOrderId,
            String rawStatus,
            String canonicalStatus,
            String paymentStatus,
            String currency,
            BigDecimal subtotalAmount,
            BigDecimal shippingAmount,
            BigDecimal discountAmount,
            BigDecimal totalAmount,
            String customerName,
            String customerPhone,
            Map<String, Object> shippingAddress,
            String externalPackageId,
            String trackingNumber,
            String shippingProvider,
            Instant createdAt,
            Instant updatedAt,
            List<OrderItemPayload> items) {
    }

    record OrderItemPayload(
            String externalOrderItemId,
            String externalProductId,
            String externalSkuId,
            String sellerSku,
            String productName,
            String variantName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal discountAmount,
            BigDecimal paidAmount) {
    }
}
