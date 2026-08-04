package com.backend.controller;

import com.backend.dto.OrderResponse;
import com.backend.dto.OrderStatusUpdateRequest;
import com.backend.security.TenantPrincipal;
import com.backend.service.OrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Order Management")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER.READ')")
    public Page<OrderResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "externalCreatedAt"));
        return orderService.list(principal.tenantId(), search, status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER.READ')")
    public OrderResponse getById(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id
    ) {
        return orderService.getById(principal.tenantId(), id);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ORDER.FULFILL')")
    public OrderResponse updateStatus(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody OrderStatusUpdateRequest request
    ) {
        return orderService.updateStatus(principal, id, request);
    }
}
