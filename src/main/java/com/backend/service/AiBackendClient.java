package com.backend.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.backend.config.AiBackendProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AiBackendClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiBackendClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AiBackendClient(
            RestClient.Builder builder,
            AiBackendProperties properties) {
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Service-Token", properties.serviceToken())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public Object createSuggestion(
            String tenantId,
            String userId,
            String conversationId,
            String triggerMessageId,
            String requestId) {
        Map<String, String> body = Map.of(
                "conversation_id", conversationId,
                "trigger_message_id", triggerMessageId);
        return post("/api/v1/runs", tenantId, userId, requestId, body);
    }

    public Object latestRun(String tenantId, String userId, String conversationId) {
        return get("/api/v1/conversations/" + conversationId + "/runs/latest", tenantId, userId);
    }

    public Object getRun(String tenantId, String userId, String runId) {
        return get("/api/v1/runs/" + runId, tenantId, userId);
    }

    public Object approve(
            String tenantId,
            String userId,
            String runId,
            String correctedText,
            boolean send) {
        ObjectNode body = objectMapper.createObjectNode().put("send", send);
        if (correctedText != null && !correctedText.isBlank()) {
            body.put("corrected_text", correctedText.trim());
        }
        return post("/api/v1/runs/" + runId + "/approve", tenantId, userId, null, body);
    }

    public Object reject(String tenantId, String userId, String runId, String reason) {
        ObjectNode body = objectMapper.createObjectNode().put("reason", reason.trim());
        return post("/api/v1/runs/" + runId + "/reject", tenantId, userId, null, body);
    }

    public Object feedback(
            String tenantId,
            String userId,
            String runId,
            int rating,
            String feedbackType,
            String commentText,
            String correctedText) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("ai_response_run_id", runId)
                .put("rating", rating)
                .put("feedback_type", feedbackType);
        putNullable(body, "comment_text", commentText);
        putNullable(body, "corrected_text", correctedText);
        return post("/api/v1/feedback", tenantId, userId, null, body);
    }

    private Object get(String path, String tenantId, String userId) {
        try {
            JsonNode envelope = restClient.get()
                    .uri(path)
                    .header("X-Tenant-Id", tenantId)
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(JsonNode.class);
            return data(envelope);
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private Object post(
            String path,
            String tenantId,
            String userId,
            String idempotencyKey,
            Object body) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Tenant-Id", tenantId)
                    .header("X-User-Id", userId);
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", "management:" + idempotencyKey);
            }
            JsonNode envelope = request.body(body).retrieve().body(JsonNode.class);
            return data(envelope);
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private Object data(JsonNode envelope) {
        if (envelope == null || !envelope.has("data")) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AI_INVALID_RESPONSE",
                    "AI Backend trả về dữ liệu không hợp lệ.");
        }
        return objectMapper.convertValue(envelope.get("data"), Object.class);
    }

    private ApiException translate(RestClientResponseException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        HttpStatus resolved = status == null ? HttpStatus.BAD_GATEWAY : status;
        String code = "AI_BACKEND_ERROR";
        String message = "AI Backend không thể xử lý yêu cầu.";
        try {
            JsonNode problem = objectMapper.readTree(
                    new String(exception.getResponseBodyAsByteArray(), StandardCharsets.UTF_8));
            if (problem.hasNonNull("code")) {
                code = problem.get("code").asText(code);
            }
            if (problem.hasNonNull("message")) {
                message = problem.get("message").asText(message);
            } else if (problem.hasNonNull("detail")) {
                JsonNode detail = problem.get("detail");
                if (detail.isTextual()) {
                    message = detail.asText(message);
                } else if (detail.isArray()) {
                    StringBuilder details = new StringBuilder();
                    detail.forEach(item -> {
                        String location = item.path("loc").isArray()
                                ? item.path("loc").toString()
                                : "request";
                        String reason = item.path("msg").asText("Dữ liệu không hợp lệ.");
                        if (details.length() > 0) {
                            details.append("; ");
                        }
                        details.append(location).append(": ").append(reason);
                    });
                    if (details.length() > 0) {
                        message = details.toString();
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep the stable public error instead of leaking an upstream response.
        }
        return new ApiException(resolved, code, message);
    }

    private ApiException unavailable(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return apiException;
        }
        LOGGER.error("AI Backend request failed before a valid response could be returned.", exception);
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AI_BACKEND_UNAVAILABLE",
                "Không thể kết nối AI Backend. Hãy kiểm tra dịch vụ cổng 8083.");
    }

    private void putNullable(ObjectNode body, String field, String value) {
        if (value == null || value.isBlank()) {
            body.putNull(field);
        } else {
            body.put(field, value.trim());
        }
    }
}
