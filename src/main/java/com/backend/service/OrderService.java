package com.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.OrderResponse;
import com.backend.dto.OrderStatusUpdateRequest;
import com.backend.entity.OrderEntity;
import com.backend.entity.OrderItemEntity;
import com.backend.repository.OrderItemRepository;
import com.backend.repository.OrderRepository;
import com.backend.repository.ShipmentRepository;
import com.backend.security.TenantPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional
public class OrderService {

    private static final Set<String> STATUSES = Set.of(
            "CREATED", "CONFIRMED", "READY_TO_SHIP", "SHIPPED", "IN_TRANSIT",
            "DELIVERED", "CANCELLED", "RETURN_REQUESTED", "RETURNED", "FAILED");

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "CREATED", Set.of("CONFIRMED", "CANCELLED"),
            "CONFIRMED", Set.of("READY_TO_SHIP", "CANCELLED"),
            "READY_TO_SHIP", Set.of("SHIPPED", "CANCELLED"),
            "SHIPPED", Set.of("IN_TRANSIT", "DELIVERED", "FAILED"),
            "IN_TRANSIT", Set.of("DELIVERED", "RETURN_REQUESTED", "FAILED"),
            "DELIVERED", Set.of("RETURN_REQUESTED"),
            "RETURN_REQUESTED", Set.of("RETURNED"));

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRepository shipmentRepository;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrderService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            ShipmentRepository shipmentRepository,
            JdbcClient jdbcClient) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.shipmentRepository = shipmentRepository;
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> list(
            String tenantId,
            String search,
            String status,
            Pageable pageable) {
        String keyword = normalize(search);
        String normalizedStatus = normalizeStatus(status, true);
        Page<OrderEntity> page;

        if (!keyword.isEmpty() && !normalizedStatus.isEmpty()) {
            page = orderRepository.searchByKeywordAndStatus(
                    tenantId, keyword, normalizedStatus, unsorted(pageable));
        } else if (!keyword.isEmpty()) {
            page = orderRepository.searchByKeyword(tenantId, keyword, unsorted(pageable));
        } else if (!normalizedStatus.isEmpty()) {
            page = orderRepository.findAllByTenantIdAndStatusAndDeletedAtIsNull(
                    tenantId, normalizedStatus, pageable);
        } else {
            page = orderRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable);
        }
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(String tenantId, String id) {
        return toResponse(findOrThrow(tenantId, id));
    }

    public OrderResponse updateStatus(
            TenantPrincipal principal,
            String id,
            OrderStatusUpdateRequest request) {
        OrderEntity order = findOrThrow(principal.tenantId(), id);
        String newStatus = normalizeStatus(request.status(), false);
        String currentStatus = order.getStatus();

        if (newStatus.equals(currentStatus)) {
            return toResponse(order);
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(newStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS_TRANSITION",
                    "Không thể chuyển trạng thái đơn từ " + currentStatus + " sang " + newStatus + ".");
        }

        Instant now = Instant.now();
        order.setStatus(newStatus);
        if (request.note() != null && !request.note().isBlank()) {
            order.setInternalNote(request.note().trim());
        }
        order.setUpdatedAt(now);
        order.setVersion(order.getVersion() + 1);
        orderRepository.saveAndFlush(order);

        jdbcClient.sql("""
                INSERT INTO order_status_history (
                  id, tenant_id, order_id, from_raw_status, to_raw_status,
                  from_canonical_status, to_canonical_status, source,
                  changed_by_user_id, reason_code, occurred_at, created_at
                ) VALUES (
                  :id, :tenantId, :orderId, :rawStatus, :rawStatus,
                  :fromStatus, :toStatus, 'USER_ACTION',
                  :userId, :reasonCode, :occurredAt, :createdAt
                )
                """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", principal.tenantId())
                .param("orderId", order.getId())
                .param("rawStatus", order.getRawStatus())
                .param("fromStatus", currentStatus)
                .param("toStatus", newStatus)
                .param("userId", principal.userId())
                .param("reasonCode", "INTERNAL_STATUS_UPDATE")
                .param("occurredAt", Timestamp.from(now))
                .param("createdAt", Timestamp.from(now))
                .update();
        return toResponse(order);
    }

    private OrderEntity findOrThrow(String tenantId, String id) {
        return orderRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                        "Không tìm thấy đơn hàng."));
    }

    private OrderResponse toResponse(OrderEntity order) {
        List<OrderItemEntity> items = orderItemRepository
                .findAllByOrderIdAndTenantIdOrderByCreatedAtAsc(order.getId(), order.getTenantId());
        List<OrderResponse.OrderItemResponse> itemResponses = items.stream()
                .map(item -> new OrderResponse.OrderItemResponse(
                        item.getId(), item.getProductName(), item.getSku(), item.getVariantName(),
                        zero(item.getPrice()), item.getQuantity() == null ? 0 : item.getQuantity(),
                        zero(item.getPaidAmount()), item.getStatus()))
                .toList();

        JsonNode address = readJson(order.getShippingAddressJson());
        String customerName = firstText(address,
                "recipientName", "recipient_name", "fullName", "full_name", "name");
        String customerPhone = firstText(address,
                "phoneNumber", "phone_number", "phone", "mobile");
        String marketplace = orderRepository.findMarketplaceCode(order.getMarketplaceAccountId())
                .map(OrderService::displayMarketplace)
                .orElse("Không xác định");
        String trackingNumber = shipmentRepository
                .findFirstByTenantIdAndOrderIdOrderByCreatedAtDesc(order.getTenantId(), order.getId())
                .map(shipment -> shipment.getTrackingNumber())
                .orElse(null);

        return new OrderResponse(
                order.getId(), order.getTenantId(), order.getExternalOrderId(),
                order.getExternalOrderId(), marketplace, customerName, customerPhone,
                safeJson(order.getShippingAddressJson()), zero(order.getSubtotalAmount()),
                zero(order.getShippingAmount()), zero(order.getDiscountAmount()),
                zero(order.getTotalAmount()), order.getPaymentStatus(), order.getRefundStatus(),
                order.getStatus(), trackingNumber, itemResponses,
                order.getExternalCreatedAt(), order.getUpdatedAt());
    }

    private String normalizeStatus(String value, boolean allowEmpty) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "PENDING" -> "CREATED";
            case "PACKED" -> "READY_TO_SHIP";
            default -> normalized;
        };
        if (normalized.isEmpty() && allowEmpty) {
            return "";
        }
        if (!STATUSES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_STATUS",
                    "Trạng thái đơn hàng không hợp lệ.");
        }
        return normalized;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(safeJson(json));
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private static String displayMarketplace(String code) {
        return switch (code) {
            case "TIKTOK_SHOP" -> "TikTok Shop";
            case "LAZADA" -> "Lazada";
            default -> code;
        };
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String safeJson(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
