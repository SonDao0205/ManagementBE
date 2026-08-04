package com.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.ProductVariantEntity;

public interface ProductVariantRepository extends JpaRepository<ProductVariantEntity, String> {

    List<ProductVariantEntity> findAllByProductIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            String productId, String tenantId);

    List<ProductVariantEntity> findAllByProductIdAndTenantId(String productId, String tenantId);

    Optional<ProductVariantEntity> findByProductIdAndTenantIdAndSkuIgnoreCase(
            String productId, String tenantId, String sku);
}
