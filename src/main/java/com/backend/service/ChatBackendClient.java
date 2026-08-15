package com.backend.service;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backend.config.ChatBackendProperties;
import com.backend.dto.AiShopKnowledgeStatusResponse;

import tools.jackson.databind.JsonNode;

@Component
public class ChatBackendClient {

    private final RestClient restClient;

    public ChatBackendClient(
            RestClient.Builder builder,
            ChatBackendProperties properties) {
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Service-Token", properties.serviceToken())
                .build();
    }

    public void scanPendingAutopilotMessage(String tenantId, String conversationId) {
        try {
            JsonNode response = restClient.post()
                    .uri("/api/v1/internal/ai/autopilot/scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-Id", tenantId)
                    .body(Map.of("conversationId", conversationId))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.path("code").asInt(-1) != 0) {
                throw unavailable("Chat Backend trả về dữ liệu không hợp lệ.");
            }
        } catch (RestClientResponseException exception) {
            throw unavailable("Chat Backend từ chối lệnh bắt đầu Autopilot.");
        } catch (RuntimeException exception) {
            if (exception instanceof ApiException apiException) {
                throw apiException;
            }
            throw unavailable("Không thể kết nối Chat Backend tại cổng 8082.");
        }
    }

    public void notifyOrderStatusUpdated(
            String tenantId,
            String orderId,
            String externalOrderId,
            String status) {
        try {
            restClient.post()
                    .uri("/api/v1/internal/orders/status-updated")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-Id", tenantId)
                    .body(Map.of(
                            "orderId", orderId,
                            "externalOrderId", externalOrderId,
                            "status", status))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            // Order synchronization must remain successful even if realtime notification is down.
        }
    }

    public AiShopKnowledgeStatusResponse getShopKnowledgeStatus(
            String tenantId,
            String marketplaceAccountId) {
        try {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/internal/ai/shop-knowledge/status")
                            .queryParam("marketplaceAccountId", marketplaceAccountId)
                            .build())
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(JsonNode.class);
            requireSuccess(response);
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull()) return null;
            return new AiShopKnowledgeStatusResponse(
                    data.path("marketplaceAccountId").asText(),
                    data.path("status").asText(),
                    nullableText(data, "catalogVersion"),
                    data.path("productCount").asInt(),
                    data.path("variantCount").asInt(),
                    data.path("missingColorCount").asInt(),
                    data.path("missingSizeCount").asInt(),
                    data.path("indexedPoints").asInt(),
                    data.path("cacheStatus").asText(),
                    data.path("vectorStatus").asText(),
                    nullableText(data, "lastBuiltAt"),
                    nullableText(data, "lastIndexedAt"),
                    nullableText(data, "lastError"));
        } catch (RestClientResponseException exception) {
            throw unavailable("Không lấy được trạng thái kiến thức shop từ Chat Backend.");
        } catch (RuntimeException exception) {
            if (exception instanceof ApiException apiException) throw apiException;
            throw unavailable("Không thể kết nối Chat Backend tại cổng 8082.");
        }
    }

    private static void requireSuccess(JsonNode response) {
        if (response == null || response.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Chat Backend trả về dữ liệu không hợp lệ.");
        }
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : value.asText();
    }

    private ApiException unavailable(String detail) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "CHAT_BACKEND_UNAVAILABLE",
                detail);
    }
}
