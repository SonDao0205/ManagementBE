package com.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MarketplaceAuthorizeRequest(
        @NotBlank
        @Pattern(regexp = "TIKTOK_SHOP|LAZADA")
        String marketplace,
        @NotBlank
        @Size(max = 500)
        String returnUrl) {
}
