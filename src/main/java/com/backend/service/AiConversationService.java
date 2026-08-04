package com.backend.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.dto.AiApprovalRequest;
import com.backend.dto.AiConversationModeResponse;
import com.backend.dto.AiFeedbackRequest;
import com.backend.dto.AiRejectionRequest;
import com.backend.dto.AiSuggestionRequest;
import com.backend.security.TenantPrincipal;

@Service
public class AiConversationService {

    private final AiBackendClient aiBackendClient;
    private final ChatBackendClient chatBackendClient;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public AiConversationService(
            AiBackendClient aiBackendClient,
            ChatBackendClient chatBackendClient,
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate) {
        this.aiBackendClient = aiBackendClient;
        this.chatBackendClient = chatBackendClient;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    public Object createSuggestion(
            TenantPrincipal principal,
            String conversationId,
            AiSuggestionRequest request) {
        requireConversation(principal.tenantId(), conversationId);
        requireMessage(principal.tenantId(), conversationId, request.triggerMessageId());
        try {
            return aiBackendClient.createSuggestion(
                    principal.tenantId(),
                    principal.userId(),
                    conversationId,
                    request.triggerMessageId(),
                    request.requestId());
        } catch (RuntimeException exception) {
            handoffAfterAiFailure(
                    principal.tenantId(), conversationId, aiFailureReason(exception));
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public Object latestRun(TenantPrincipal principal, String conversationId) {
        requireConversation(principal.tenantId(), conversationId);
        return aiBackendClient.latestRun(principal.tenantId(), principal.userId(), conversationId);
    }

    public Object getRun(TenantPrincipal principal, String runId) {
        try {
            return aiBackendClient.getRun(principal.tenantId(), principal.userId(), runId);
        } catch (RuntimeException exception) {
            String conversationId = jdbcClient.sql("""
                        SELECT conversation_id
                        FROM ai_response_runs
                        WHERE id = :runId AND tenant_id = :tenantId
                        LIMIT 1
                        """)
                    .param("runId", runId)
                    .param("tenantId", principal.tenantId())
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (conversationId != null) {
                handoffAfterAiFailure(
                        principal.tenantId(), conversationId, aiFailureReason(exception));
            }
            throw exception;
        }
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

    public AiConversationModeResponse setMode(
            TenantPrincipal principal, String conversationId, String mode) {
        AiConversationModeResponse result = transactionTemplate.execute(status -> {
            updateMode(principal.tenantId(), conversationId, mode);
            return new AiConversationModeResponse(conversationId, mode);
        });
        if (result == null) {
            throw new IllegalStateException("Không thể cập nhật chế độ AI.");
        }

        if ("AUTO".equals(mode)) {
            try {
                chatBackendClient.scanPendingAutopilotMessage(
                        principal.tenantId(), conversationId);
            } catch (RuntimeException exception) {
                transactionTemplate.executeWithoutResult(status ->
                        updateMode(principal.tenantId(), conversationId, "HUMAN_ONLY"));
                throw exception;
            }
        }
        return result;
    }

    private void updateMode(String tenantId, String conversationId, String mode) {
        requireConversation(tenantId, conversationId);
        int updated = jdbcClient.sql("""
                    UPDATE conversations
                    SET ai_mode = :mode,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :conversationId
                      AND tenant_id = :tenantId
                    """)
                .param("mode", mode)
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .update();
        if (updated != 1) {
            throw notFound("Hội thoại không tồn tại trong shop hiện tại.");
        }
        if (!"HUMAN_ONLY".equals(mode)) {
            jdbcClient.sql("""
                        UPDATE human_handoffs
                        SET status = 'CANCELLED',
                            resolved_at = CURRENT_TIMESTAMP,
                            resolution_note = 'Nhân viên đã bật lại chế độ AI.'
                        WHERE conversation_id = :conversationId
                          AND tenant_id = :tenantId
                          AND status IN ('REQUESTED', 'NOTIFIED', 'ACCEPTED')
                        """)
                    .param("conversationId", conversationId)
                    .param("tenantId", tenantId)
                    .update();
        }
    }

    private void handoffAfterAiFailure(
            String tenantId, String conversationId, String reasonText) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                requireConversation(tenantId, conversationId);
                jdbcClient.sql("""
                            UPDATE conversations
                            SET ai_mode = 'HUMAN_ONLY',
                                priority = 'HIGH',
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = :conversationId AND tenant_id = :tenantId
                            """)
                        .param("conversationId", conversationId)
                        .param("tenantId", tenantId)
                        .update();

                String handoffId = UUID.randomUUID().toString();
                int inserted = jdbcClient.sql("""
                            INSERT INTO human_handoffs (
                                id, tenant_id, conversation_id, ai_response_run_id,
                                reason_code, reason_text, priority, status
                            )
                            SELECT :id, :tenantId, :conversationId, NULL,
                                   'AI_SERVICE_UNAVAILABLE', :reasonText, 'HIGH', 'REQUESTED'
                            WHERE NOT EXISTS (
                                SELECT 1 FROM human_handoffs
                                WHERE tenant_id = :tenantId
                                  AND conversation_id = :conversationId
                                  AND status IN ('REQUESTED', 'NOTIFIED', 'ACCEPTED')
                            )
                            """)
                        .param("id", handoffId)
                        .param("tenantId", tenantId)
                        .param("conversationId", conversationId)
                        .param("reasonText", reasonText)
                        .update();

                if (inserted > 0) {
                    jdbcClient.sql("""
                                INSERT INTO notifications (
                                    id, tenant_id, recipient_user_id, notification_type,
                                    channel, title, body_text, reference_type,
                                    reference_id, status
                                ) VALUES (
                                    :id, :tenantId, NULL, 'AI_HUMAN_HANDOFF',
                                    'IN_APP', 'Hội thoại cần nhân viên xử lý',
                                    :reasonText, 'CONVERSATION', :conversationId, 'QUEUED'
                                )
                                """)
                            .param("id", UUID.randomUUID().toString())
                            .param("tenantId", tenantId)
                            .param("reasonText", reasonText)
                            .param("conversationId", conversationId)
                            .update();
                }
            });
        } catch (RuntimeException persistenceException) {
            // Preserve the original AI error returned to the caller.
        }
    }

    private String aiFailureReason(RuntimeException exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = "Dịch vụ AI không thể tạo câu trả lời.";
        }
        String reason = "AI không thể trả lời: " + detail;
        return reason.length() <= 4000 ? reason : reason.substring(0, 4000);
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
