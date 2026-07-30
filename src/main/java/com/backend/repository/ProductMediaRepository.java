package com.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.ProductMediaEntity;

public interface ProductMediaRepository extends JpaRepository<ProductMediaEntity, String> {

    Optional<ProductMediaEntity>
            findFirstByTenantIdAndProductIdAndPrimaryTrueAndDeletedAtIsNullOrderBySortOrderAsc(
                    String tenantId,
                    String productId);
}
