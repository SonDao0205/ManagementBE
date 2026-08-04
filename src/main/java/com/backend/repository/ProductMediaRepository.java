package com.backend.repository;

import java.util.List;
import java.util.Optional;

import com.backend.entity.ProductMediaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductMediaRepository extends JpaRepository<ProductMediaEntity, String> {

    List<ProductMediaEntity> findAllByProductIdAndTenantIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
            String productId,
            String tenantId);

    Optional<ProductMediaEntity> findByIdAndProductIdAndTenantIdAndDeletedAtIsNull(
            String id,
            String productId,
            String tenantId);
}
