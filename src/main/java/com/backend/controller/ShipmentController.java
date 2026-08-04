package com.backend.controller;

import com.backend.dto.ShipmentOverviewResponse;
import com.backend.dto.ShipmentResponse;
import com.backend.security.TenantPrincipal;
import com.backend.service.ShipmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/shipments")
@Tag(name = "Shipment Tracking")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
@Validated
public class ShipmentController {

    private final ShipmentService shipmentService;

    @GetMapping
    public Page<ShipmentResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return shipmentService.list(principal.tenantId(), search, pageable);
    }

    @GetMapping("/track/{code}")
    public ShipmentResponse track(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String code
    ) {
        return shipmentService.track(principal.tenantId(), code);
    }

    @GetMapping("/overview")
    public ShipmentOverviewResponse overview(
            @AuthenticationPrincipal TenantPrincipal principal
    ) {
        return shipmentService.overview(principal.tenantId());
    }
}
