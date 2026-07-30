package com.backend.controller;

import com.backend.dto.OrderRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Order Management")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public Page<OrderResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderService.list(principal.tenantId(), search, status, pageable);
    }

    @GetMapping("/{id}")
    public OrderResponse getById(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id
    ) {
        return orderService.getById(principal.tenantId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody OrderRequest request
    ) {
        return orderService.create(principal.tenantId(), request);
    }

    @PutMapping("/{id}/status")
    public OrderResponse updateStatus(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody OrderStatusUpdateRequest request
    ) {
        return orderService.updateStatus(principal.tenantId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id
    ) {
        orderService.delete(principal.tenantId(), id);
    }
}
