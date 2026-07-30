package com.backend.dto;

import jakarta.validation.constraints.NotNull;

public record StockAdjustmentRequest(

        /** Positive value = stock in; negative value = stock out. */
        @NotNull
        Integer delta,

        String note) {
}
