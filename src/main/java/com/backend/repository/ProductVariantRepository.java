package com.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.ProductVariantEntity;

public interface ProductVariantRepository extends JpaRepository<ProductVariantEntity, String> {

    List<ProductVariantEntity> findAllByProductIdAndTenantId(String productId, String tenantId);
}
