package com.backend.dto;

import com.backend.entity.OrderEntity;
import com.backend.entity.OrderItemEntity;

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
        BigDecimal discountAmount,
        BigDecimal finalAmount,
        String paymentStatus,
        String status,
        List<OrderItemResponse> items,
        Instant createdAt
) {
    public record OrderItemResponse(
            String id,
            String productName,
            String sku,
            String variantName,
            BigDecimal price,
            int quantity
    ) {}

    public static OrderResponse from(OrderEntity order, List<OrderItemEntity> itemEntities) {
        List<OrderItemResponse> itemResponses = itemEntities.stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getProductName(),
                        item.getSku(),
                        item.getVariantName(),
                        item.getPrice(),
                        item.getQuantity() != null ? item.getQuantity() : 0
                ))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getTenantId(),
                order.getOrderCode(),
                order.getExternalOrderId(),
                order.getMarketplace(),
                order.getCustomerName(),
                order.getCustomerPhone(),
                order.getShippingAddressJson(),
                order.getTotalAmount(),
                order.getDiscountAmount(),
                order.getFinalAmount(),
                order.getPaymentStatus(),
                order.getStatus(),
                itemResponses,
                order.getCreatedAt()
        );
    }
}
