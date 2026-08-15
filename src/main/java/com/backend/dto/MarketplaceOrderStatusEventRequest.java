package com.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record MarketplaceOrderStatusEventRequest(
        @NotBlank String marketplace,
        @NotBlank String externalOrderId,
        @NotBlank String status) {
}
