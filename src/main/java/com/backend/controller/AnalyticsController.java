package com.backend.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.dto.DashboardOverviewResponse;
import com.backend.dto.RevenueAnalyticsResponse;
import com.backend.security.TenantPrincipal;
import com.backend.service.AnalyticsService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('REPORT.READ')")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/dashboard/overview")
    public DashboardOverviewResponse overview(
            @AuthenticationPrincipal TenantPrincipal principal) {
        return analyticsService.overview(principal.tenantId());
    }

    @GetMapping("/analytics/revenue")
    public RevenueAnalyticsResponse revenue(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(defaultValue = "this-month") String period) {
        return analyticsService.revenue(principal.tenantId(), period);
    }
}
