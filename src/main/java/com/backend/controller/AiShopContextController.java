package com.backend.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.backend.dto.AiShopContextActivationRequest;
import com.backend.dto.AiShopContextRequest;
import com.backend.dto.AiShopContextResponse;
import com.backend.security.TenantPrincipal;
import com.backend.service.AiShopContextService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/ai-contexts")
@Tag(name = "AI shop contexts")
@Validated
@PreAuthorize("hasAuthority('AI.CONFIGURE')")
public class AiShopContextController {

    private final AiShopContextService contextService;

    public AiShopContextController(AiShopContextService contextService) {
        this.contextService = contextService;
    }

    @GetMapping
    @Operation(summary = "Liệt kê ngữ cảnh AI riêng của một shop")
    public List<AiShopContextResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam @NotBlank @Size(max = 36) String shopId) {
        return contextService.list(principal, shopId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo ngữ cảnh AI cho shop")
    public AiShopContextResponse create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AiShopContextRequest request) {
        return contextService.create(principal, request);
    }

    @PutMapping("/{contextId}")
    @Operation(summary = "Cập nhật ngữ cảnh AI")
    public AiShopContextResponse update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String contextId,
            @Valid @RequestBody AiShopContextRequest request) {
        return contextService.update(principal, contextId, request);
    }

    @PatchMapping("/{contextId}/activation")
    @Operation(summary = "Active hoặc tắt ngữ cảnh; active một dòng sẽ tắt dòng khác cùng shop")
    public AiShopContextResponse setActive(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String contextId,
            @Valid @RequestBody AiShopContextActivationRequest request) {
        return contextService.setActive(principal, contextId, request.active());
    }

    @DeleteMapping("/{contextId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa mềm ngữ cảnh AI")
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String contextId) {
        contextService.delete(principal, contextId);
    }
}
