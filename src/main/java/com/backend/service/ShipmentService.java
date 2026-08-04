package com.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.ShipmentOverviewResponse;
import com.backend.dto.ShipmentResponse;
import com.backend.entity.OrderEntity;
import com.backend.entity.ShipmentEntity;
import com.backend.repository.OrderRepository;
import com.backend.repository.ShipmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ShipmentService(
            ShipmentRepository shipmentRepository,
            OrderRepository orderRepository) {
        this.shipmentRepository = shipmentRepository;
        this.orderRepository = orderRepository;
    }

    public Page<ShipmentResponse> list(String tenantId, String search, Pageable pageable) {
        String keyword = normalize(search);
        Page<ShipmentEntity> page = keyword.isEmpty()
                ? shipmentRepository.findAllByTenantId(tenantId, pageable)
                : shipmentRepository.search(tenantId, keyword,
                        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        return page.map(this::toResponse);
    }

    public ShipmentResponse track(String tenantId, String code) {
        String normalizedCode = normalize(code);
        if (normalizedCode.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRACKING_CODE_REQUIRED",
                    "Vui lòng nhập mã vận đơn, mã kiện hoặc mã đơn hàng.");
        }
        ShipmentEntity shipment = shipmentRepository.track(tenantId, normalizedCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SHIPMENT_NOT_FOUND",
                        "Không tìm thấy thông tin vận chuyển."));
        return toResponse(shipment);
    }

    public ShipmentOverviewResponse overview(String tenantId) {
        List<ShipmentEntity> shipments = shipmentRepository
                .findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        long waiting = count(shipments, "CREATED", "READY_TO_SHIP");
        long picked = count(shipments, "SHIPPED");
        long transit = count(shipments, "IN_TRANSIT");
        long failed = count(shipments, "FAILED", "RETURNED", "CANCELLED");
        long success = count(shipments, "DELIVERED");

        return new ShipmentOverviewResponse(
                waiting, picked, transit, failed, success,
                averageDeliveryHours(shipments, "GHTK"),
                averageDeliveryHours(shipments, "GHN"),
                successRate(shipments, "GHTK"),
                successRate(shipments, "GHN"));
    }

    private ShipmentResponse toResponse(ShipmentEntity shipment) {
        OrderEntity order = orderRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(shipment.getOrderId(), shipment.getTenantId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "SHIPMENT_ORDER_NOT_FOUND",
                        "Vận đơn không còn liên kết với đơn hàng hợp lệ."));
        JsonNode address = readJson(order.getShippingAddressJson());
        String destination = firstText(address,
                "fullAddress", "full_address", "address", "city", "province");
        BigDecimal codAmount = "UNPAID".equals(order.getPaymentStatus())
                ? zero(order.getTotalAmount()) : BigDecimal.ZERO;

        return new ShipmentResponse(
                shipment.getId(), shipment.getTenantId(), shipment.getOrderId(),
                order.getExternalOrderId(), shipment.getExternalPackageId(),
                shipment.getTrackingNumber(), shipment.getShippingProvider(), destination,
                codAmount, shipment.getRawStatus(), milestoneType(shipment.getStatus()),
                shipment.getStatus(), shipment.getReadyToShipAt(), shipment.getShippedAt(),
                shipment.getDeliveredAt(), shipment.getCreatedAt(), shipment.getUpdatedAt());
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
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

    private static String milestoneType(String status) {
        return switch (status) {
            case "CREATED", "READY_TO_SHIP" -> "waiting";
            case "SHIPPED" -> "picked";
            case "IN_TRANSIT" -> "transit";
            case "DELIVERED" -> "success";
            default -> "failed";
        };
    }

    private static long count(List<ShipmentEntity> shipments, String... statuses) {
        List<String> accepted = List.of(statuses);
        return shipments.stream().filter(item -> accepted.contains(item.getStatus())).count();
    }

    private static double averageDeliveryHours(
            List<ShipmentEntity> shipments,
            String providerToken) {
        return shipments.stream()
                .filter(item -> providerContains(item, providerToken))
                .filter(item -> item.getShippedAt() != null && item.getDeliveredAt() != null)
                .filter(item -> !item.getDeliveredAt().isBefore(item.getShippedAt()))
                .mapToLong(item -> Duration.between(item.getShippedAt(), item.getDeliveredAt()).toMinutes())
                .average()
                .stream()
                .map(minutes -> round(minutes / 60.0))
                .findFirst()
                .orElse(0.0);
    }

    private static double successRate(List<ShipmentEntity> shipments, String providerToken) {
        long total = shipments.stream().filter(item -> providerContains(item, providerToken)).count();
        if (total == 0) {
            return 0.0;
        }
        long delivered = shipments.stream()
                .filter(item -> providerContains(item, providerToken))
                .filter(item -> "DELIVERED".equals(item.getStatus()))
                .count();
        return round(delivered * 100.0 / total);
    }

    private static boolean providerContains(ShipmentEntity shipment, String token) {
        return shipment.getShippingProvider() != null
                && shipment.getShippingProvider().toUpperCase(Locale.ROOT).contains(token);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
