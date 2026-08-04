package com.backend.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record ProductMediaOrderRequest(
        @NotEmpty(message = "Danh sách media không được để trống.")
        @Valid
        List<Item> items
) {
    public record Item(
            @NotBlank String mediaId,
            @NotNull @Min(0) Integer sortOrder,
            boolean primary
    ) {
    }
}
