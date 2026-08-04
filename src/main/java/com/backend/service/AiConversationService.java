package com.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.AiApprovalRequest;
import com.backend.dto.AiConversationModeResponse;
import com.backend.dto.AiFeedbackRequest;
import com.backend.dto.AiRejectionRequest;
import com.backend.dto.AiSuggestionRequest;
import com.backend.security.TenantPrincipal;

@Service
public class AiConversationService {

    private final AiBackendClient aiBackendClient;
    private final JdbcClient jdbcClient;

    public AiConversationService(AiBackendClient aiBackendClient, JdbcClient jdbcClient) {
        this.aiBackendClient = aiBackendClient;
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public Object createSuggestion(
            TenantPrincipal principal,
            String conversationId,
            AiSuggestionRequest request) {
        requireConversation(principal.tenantId(), conversationId);
        requireMessage(principal.tenantId(), conversationId, request.triggerMessageId());
        return aiBackendClient.createSuggestion(
                principal.tenantId(),
                principal.userId(),
                conversationId,
                request.triggerMessageId(),
                request.requestId());
    }

    @Transactional(readOnly = true)
    public Object latestRun(TenantPrincipal principal, String conversationId) {
        requireConversation(principal.tenantId(), conversationId);
        return aiBackendClient.latestRun(principal.tenantId(), principal.userId(), conversationId);
    }

    public Object getRun(TenantPrincipal principal, String runId) {
        return aiBackendClient.getRun(principal.tenantId(), principal.userId(), runId);
    }

    public Object approve(TenantPrincipal principal, String runId, AiApprovalRequest request) {
        return aiBackendClient.approve(
                principal.tenantId(), principal.userId(), runId,
                request.correctedText(), request.send());
    }

    public Object reject(TenantPrincipal principal, String runId, AiRejectionRequest request) {
        return aiBackendClient.reject(
                principal.tenantId(), principal.userId(), runId, request.reason());
    }

    public Object feedback(TenantPrincipal principal, AiFeedbackRequest request) {
        return aiBackendClient.feedback(
                principal.tenantId(), principal.userId(), request.aiResponseRunId(),
                request.rating(), request.feedbackType(), request.commentText(),
                request.correctedText());
    }

    @Transactional
    public AiConversationModeResponse setMode(
            TenantPrincipal principal, String conversationId, String mode) {
        requireConversation(principal.tenantId(), conversationId);
        int updated = jdbcClient.sql("""
                UPDATE conversations
                SET ai_mode = :mode,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :conversationId
                  AND tenant_id = :tenantId
                """)
                .param("mode", mode)
                .param("conversationId", conversationId)
                .param("tenantId", principal.tenantId())
                .update();
        if (updated != 1) {
            throw notFound("Hội thoại không tồn tại trong shop hiện tại.");
        }
        return new AiConversationModeResponse(conversationId, mode);
    }

    private void requireConversation(String tenantId, String conversationId) {
        long count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM conversations
                WHERE id = :conversationId AND tenant_id = :tenantId
                """)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
        if (count != 1) {
            throw notFound("Hội thoại không tồn tại trong shop hiện tại.");
        }
    }

    private void requireMessage(String tenantId, String conversationId, String messageId) {
        long count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM messages
                WHERE id = :messageId
                  AND conversation_id = :conversationId
                  AND tenant_id = :tenantId
                """)
                .param("messageId", messageId)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
        if (count != 1) {
            throw notFound("Tin nhắn kích hoạt AI không tồn tại trong hội thoại này.");
        }
    }

    private ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "AI_CONVERSATION_NOT_FOUND", detail);
    }
}
