package com.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.ProductVariantEntity;

public interface ProductVariantRepository
        extends JpaRepository<ProductVariantEntity, String> {

    List<ProductVariantEntity>
            findByTenantIdAndProductIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                    String tenantId,
                    String productId);

    Optional<ProductVariantEntity>
            findByTenantIdAndProductIdAndSellerSkuAndDeletedAtIsNull(
                    String tenantId,
                    String productId,
                    String sellerSku);

    Optional<ProductVariantEntity>
            findByTenantIdAndSellerSkuAndDeletedAtIsNull(
                    String tenantId,
                    String sellerSku);
}
