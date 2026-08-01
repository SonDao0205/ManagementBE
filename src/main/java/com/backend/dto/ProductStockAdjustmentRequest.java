package com.backend.dto;

import jakarta.validation.constraints.NotNull;

public record ProductStockAdjustmentRequest(
        @NotNull Integer delta,
        String note,
        String variantId) {
}
