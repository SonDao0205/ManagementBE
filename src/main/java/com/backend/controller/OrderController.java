package com.backend.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.dto.OrderResponse;
import com.backend.dto.OrderCreateRequest;
import com.backend.dto.OrderStatusUpdateRequest;
import com.backend.dto.OrderSyncResponse;
import com.backend.security.TenantPrincipal;
import com.backend.service.OrderService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
@PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Page<OrderResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        return orderService.list(
                principal.tenantId(),
                search,
                status,
                PageRequest.of(Math.max(page, 0), safeSize));
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String orderId) {
        return orderService.get(principal.tenantId(), orderId);
    }

    @PostMapping
    public OrderResponse create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody OrderCreateRequest request) {
        return orderService.create(principal, request);
    }

    @PostMapping("/sync")
    public OrderSyncResponse sync(
            @AuthenticationPrincipal TenantPrincipal principal) {
        return orderService.sync(principal);
    }

    @PutMapping("/{orderId}/status")
    public OrderResponse updateStatus(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String orderId,
            @Valid @RequestBody OrderStatusUpdateRequest request) {
        return orderService.updateStatus(principal, orderId, request.status());
    }
}
