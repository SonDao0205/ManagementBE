package com.backend.dto;

import jakarta.validation.constraints.NotNull;

public record AiShopContextActivationRequest(
        @NotNull(message = "Vui lòng chọn trạng thái active.") Boolean active) {
}
