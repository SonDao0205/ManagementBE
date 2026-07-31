package com.backend.controller;

import com.backend.dto.ProductAIAnalyticsResponse;
import com.backend.dto.RevenueAnalyticsResponse;
import com.backend.security.TenantPrincipal;
import com.backend.service.AnalyticsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics Management")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/revenue")
    public RevenueAnalyticsResponse getRevenueAnalytics(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(defaultValue = "this-month") String period
    ) {
        return analyticsService.getRevenueAnalytics(principal.tenantId(), period);
    }

    @GetMapping("/products-ai")
    public ProductAIAnalyticsResponse getProductAIAnalytics(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(defaultValue = "this-month") String period,
            @RequestParam(defaultValue = "ALL") String channel
    ) {
        return analyticsService.getProductAIAnalytics(principal.tenantId(), period, channel);
    }
}
