package com.backend.dto;

import java.util.Map;

public record OrderStatsResponse(
        long total,
        Map<String, Long> byStatus) {
}
