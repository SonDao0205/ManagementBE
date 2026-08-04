package com.backend.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.dto.AiApprovalRequest;
import com.backend.dto.AiConversationModeRequest;
import com.backend.dto.AiConversationModeResponse;
import com.backend.dto.AiFeedbackRequest;
import com.backend.dto.AiRejectionRequest;
import com.backend.dto.AiSuggestionRequest;
import com.backend.security.TenantPrincipal;
import com.backend.service.AiConversationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/ai")
@Validated
public class AiConversationController {

    private final AiConversationService service;

    public AiConversationController(AiConversationService service) {
        this.service = service;
    }

    @PostMapping("/conversations/{conversationId}/suggestions")
    @PreAuthorize("hasAuthority('AI.SUGGEST')")
    public Object createSuggestion(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable @NotBlank @Size(max = 36) String conversationId,
            @Valid @RequestBody AiSuggestionRequest request) {
        return service.createSuggestion(principal, conversationId, request);
    }

    @GetMapping("/conversations/{conversationId}/runs/latest")
    @PreAuthorize("hasAuthority('AI.SUGGEST')")
    public Object latestRun(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable @NotBlank @Size(max = 36) String conversationId) {
        return service.latestRun(principal, conversationId);
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAuthority('AI.SUGGEST')")
    public Object getRun(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable @NotBlank @Size(max = 36) String runId) {
        return service.getRun(principal, runId);
    }

    @PostMapping("/runs/{runId}/approve")
    @PreAuthorize("hasAuthority('AI.APPROVE')")
    public Object approve(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable @NotBlank @Size(max = 36) String runId,
            @Valid @RequestBody AiApprovalRequest request) {
        return service.approve(principal, runId, request);
    }

    @PostMapping("/runs/{runId}/reject")
    @PreAuthorize("hasAuthority('AI.APPROVE')")
    public Object reject(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable @NotBlank @Size(max = 36) String runId,
            @Valid @RequestBody AiRejectionRequest request) {
        return service.reject(principal, runId, request);
    }

    @PostMapping("/feedback")
    @PreAuthorize("hasAuthority('AI.SUGGEST')")
    public Object feedback(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AiFeedbackRequest request) {
        return service.feedback(principal, request);
    }

    @PatchMapping("/conversations/{conversationId}/mode")
    @PreAuthorize("hasAuthority('AI.SUGGEST')")
    public AiConversationModeResponse setMode(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable @NotBlank @Size(max = 36) String conversationId,
            @Valid @RequestBody AiConversationModeRequest request) {
        return service.setMode(principal, conversationId, request.mode());
    }
}
