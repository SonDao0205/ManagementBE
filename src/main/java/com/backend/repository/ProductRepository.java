package com.backend.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.entity.ProductEntity;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {

    @Query("""
            SELECT product
            FROM ProductEntity product
            WHERE product.tenantId = :tenantId
              AND product.deletedAt IS NULL
              AND (:status IS NULL OR product.status = :status)
              AND (
                    :search IS NULL
                    OR LOWER(product.productName) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(product.productCode) LIKE LOWER(CONCAT('%', :search, '%'))
                    OR LOWER(COALESCE(product.internalCategoryCode, ''))
                       LIKE LOWER(CONCAT('%', :search, '%'))
              )
            """)
    Page<ProductEntity> search(
            @Param("tenantId") String tenantId,
            @Param("search") String search,
            @Param("status") String status,
            Pageable pageable);

    Optional<ProductEntity> findByIdAndTenantIdAndDeletedAtIsNull(
            String id,
            String tenantId);

    boolean existsByTenantIdAndProductCodeAndDeletedAtIsNull(
            String tenantId,
            String productCode);

    boolean existsByTenantIdAndProductCode(
            String tenantId,
            String productCode);
}
