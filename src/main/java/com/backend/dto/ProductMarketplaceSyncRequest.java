package com.backend.dto;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ProductMarketplaceSyncRequest(
        boolean allProducts,

        @Size(max = 10000, message = "Mỗi lượt được chọn tối đa 10000 sản phẩm.")
        List<@Size(max = 36) String> productIds,

        @NotEmpty(message = "Vui lòng chọn ít nhất một shop cần đồng bộ.")
        @Size(max = 100, message = "Mỗi lượt được chọn tối đa 100 shop.")
        List<@Size(max = 36) String> marketplaceAccountIds) {

    @AssertTrue(message = "Vui lòng chọn sản phẩm hoặc chọn toàn bộ kho.")
    public boolean hasProductSelection() {
        return allProducts || (productIds != null && !productIds.isEmpty());
    }
}
