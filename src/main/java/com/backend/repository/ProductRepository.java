package com.backend.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.entity.ProductEntity;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {

    Page<ProductEntity> findAllByTenantIdAndDeletedAtIsNull(String tenantId, Pageable pageable);

    Optional<ProductEntity> findByIdAndTenantIdAndDeletedAtIsNull(String id, String tenantId);

    @Query("""
            SELECT p FROM ProductEntity p
            WHERE p.tenantId = :tenantId
              AND p.deletedAt IS NULL
              AND (
                  LOWER(p.name)        LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(p.productCode) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(p.category)    LIKE LOWER(CONCAT('%', :search, '%'))
              )
            """)
    Page<ProductEntity> findByTenantIdAndSearch(
            @Param("tenantId") String tenantId,
            @Param("search") String search,
            Pageable pageable);

    Page<ProductEntity> findAllByTenantIdAndStatusAndDeletedAtIsNull(
            String tenantId, String status, Pageable pageable);
}
