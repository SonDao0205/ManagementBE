package com.backend.controller;

import com.backend.config.ChatBackendProperties;
import com.backend.dto.MarketplaceOrderStatusEventRequest;
import com.backend.entity.OrderEntity;
import com.backend.repository.OrderRepository;
import com.backend.service.ChatBackendClient;
import com.backend.service.MarketplaceSyncService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/marketplace")
@RequiredArgsConstructor
public class MarketplaceOrderEventController {

    private final ChatBackendProperties chatBackendProperties;
    private final OrderRepository orderRepository;
    private final MarketplaceSyncService marketplaceSyncService;
    private final ChatBackendClient chatBackendClient;

    @PostMapping("/order-status")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> receive(
            @RequestHeader(value = "X-Service-Token", defaultValue = "") String token,
            @Valid @RequestBody MarketplaceOrderStatusEventRequest request) {
        requireServiceToken(token);
        String marketplaceCode = normalizeMarketplace(request.marketplace());
        List<OrderEntity> matchingOrders = orderRepository.findForMarketplaceStatusEvent(
                marketplaceCode, request.externalOrderId().trim());
        for (OrderEntity order : matchingOrders) {
            marketplaceSyncService.syncAccount(order.getTenantId(), order.getMarketplaceAccountId());
            OrderEntity updated = orderRepository
                    .findByMarketplaceAccountIdAndExternalOrderId(
                            order.getMarketplaceAccountId(), order.getExternalOrderId())
                    .orElse(order);
            chatBackendClient.notifyOrderStatusUpdated(
                    updated.getTenantId(), updated.getId(), updated.getExternalOrderId(),
                    updated.getStatus());
        }
        return Map.of("code", 0, "matchedOrders", matchingOrders.size());
    }

    private void requireServiceToken(String token) {
        String expected = chatBackendProperties.serviceToken();
        if (expected == null || expected.isBlank()
                || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8))) {
            throw new com.backend.service.ApiException(
                    HttpStatus.UNAUTHORIZED, "INVALID_SERVICE_TOKEN",
                    "Invalid marketplace status event token.");
        }
    }

    private static String normalizeMarketplace(String marketplace) {
        return switch (marketplace.trim().toUpperCase(Locale.ROOT)) {
            case "TIKTOK", "TIKTOK_SHOP" -> "TIKTOK_SHOP";
            case "LAZADA" -> "LAZADA";
            default -> throw new com.backend.service.ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_MARKETPLACE", "Unsupported marketplace.");
        };
    }
}
