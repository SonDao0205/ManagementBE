package com.backend.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.dto.MarketplaceAuthorizationResponse;
import com.backend.dto.MarketplaceAuthorizeRequest;
import com.backend.dto.MarketplaceConnectionResponse;
import com.backend.dto.MarketplaceSyncResponse;
import com.backend.dto.MarketplaceSyncRequest;
import com.backend.security.TenantPrincipal;
import com.backend.service.MarketplaceConnectionService;
import com.backend.service.MarketplaceSyncService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/marketplace-connections")
@Tag(name = "Marketplace connections")
public class MarketplaceConnectionController {

    private final MarketplaceConnectionService connectionService;
    private final MarketplaceSyncService syncService;

    public MarketplaceConnectionController(
            MarketplaceConnectionService connectionService,
            MarketplaceSyncService syncService) {
        this.connectionService = connectionService;
        this.syncService = syncService;
    }

    @GetMapping
    @Operation(summary = "Liệt kê các shop đã liên kết của tenant hiện tại")
    public List<MarketplaceConnectionResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal) {
        return connectionService.list(principal);
    }

    @PostMapping("/authorize")
    @PreAuthorize("hasAuthority('ACCOUNT.CONNECT')")
    @Operation(summary = "Tạo OAuth state một lần và lấy URL liên kết sàn giả lập")
    public MarketplaceAuthorizationResponse authorize(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody MarketplaceAuthorizeRequest request) {
        return connectionService.beginAuthorization(principal, request);
    }

    @GetMapping("/callback/{marketplace}")
    @PreAuthorize("hasAuthority('ACCOUNT.CONNECT')")
    @Operation(summary = "Nhận authorization code từ sàn giả lập và lưu kết nối")
    public ResponseEntity<Void> callback(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String marketplace,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam String state) {
        URI redirect = error == null || error.isBlank()
                ? connectionService.completeAuthorization(
                        principal,
                        fromPath(marketplace),
                        code,
                        state)
                : connectionService.denyAuthorization(
                        principal,
                        fromPath(marketplace),
                        state);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirect.toString())
                .build();
    }

    @PostMapping("/{accountId}/verify")
    @PreAuthorize("hasAuthority('ACCOUNT.CONNECT')")
    @Operation(summary = "Xác minh access token và đồng bộ lại hồ sơ shop")
    public MarketplaceConnectionResponse verify(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String accountId) {
        return connectionService.verify(principal, accountId);
    }

    @PostMapping("/{accountId}/refresh")
    @PreAuthorize("hasAuthority('ACCOUNT.CONNECT')")
    @Operation(summary = "Làm mới access token bằng refresh token đã mã hóa")
    public MarketplaceConnectionResponse refresh(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String accountId) {
        return connectionService.refresh(principal, accountId);
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    @Operation(summary = "Đồng bộ sản phẩm và đơn hàng từ toàn bộ shop đã liên kết")
    public MarketplaceSyncResponse syncAll(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestBody(required = false) MarketplaceSyncRequest request) {
        return request == null
                ? syncService.syncTenant(principal.tenantId())
                : syncService.syncTenant(principal.tenantId(), request);
    }

    @PostMapping("/{accountId}/sync")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    @Operation(summary = "Đồng bộ sản phẩm và đơn hàng từ một shop đã liên kết")
    public MarketplaceSyncResponse syncAccount(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String accountId) {
        return syncService.syncAccount(principal.tenantId(), accountId);
    }

    @DeleteMapping("/{accountId}")
    @PreAuthorize("hasAuthority('ACCOUNT.DISCONNECT')")
    @Operation(summary = "Thu hồi token và ngắt liên kết shop khỏi tenant")
    public ResponseEntity<Void> disconnect(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String accountId) {
        connectionService.disconnect(principal, accountId);
        return ResponseEntity.noContent().build();
    }

    private static String fromPath(String marketplace) {
        return switch (marketplace.toLowerCase()) {
            case "tiktok-shop" -> "TIKTOK_SHOP";
            case "lazada" -> "LAZADA";
            default -> marketplace;
        };
    }
}
