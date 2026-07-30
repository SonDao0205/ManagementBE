package com.backend.dto;

public record ShipmentOverviewResponse(
        long countWaiting,
        long countPicked,
        long countInTransit,
        long countFailed,
        long countSuccess,
        double ghtkAvgHours,
        double ghnAvgHours,
        double ghtkSuccessRate,
        double ghnSuccessRate
) {}
