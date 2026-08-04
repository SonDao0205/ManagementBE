package com.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String id,
        String tenantId,
        String orderCode,
        String externalOrderId,
        String marketplace,
        String customerName,
        String customerPhone,
        String shippingAddressJson,
        BigDecimal totalAmount,
        BigDecimal shippingFee,
        BigDecimal discountAmount,
        BigDecimal finalAmount,
        String paymentStatus,
        String refundStatus,
        String status,
        String trackingNumber,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt) {

    public record OrderItemResponse(
            String id,
            String productName,
            String sku,
            String variantName,
            BigDecimal price,
            int quantity,
            BigDecimal paidAmount,
            String status) {
    }
}
