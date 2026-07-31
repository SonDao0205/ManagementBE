package com.backend.repository;

import com.backend.entity.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    Page<OrderEntity> findAllByTenantIdAndDeletedAtIsNull(String tenantId, Pageable pageable);

    java.util.List<OrderEntity> findAllByTenantIdAndDeletedAtIsNull(String tenantId);

    Optional<OrderEntity> findByIdAndTenantIdAndDeletedAtIsNull(String id, String tenantId);

    Page<OrderEntity> findAllByTenantIdAndStatusAndDeletedAtIsNull(String tenantId, String status, Pageable pageable);

    long countByTenantIdAndStatusAndDeletedAtIsNull(String tenantId, String status);

    @Query("""
            SELECT o FROM OrderEntity o
            WHERE o.tenantId = :tenantId
              AND o.deletedAt IS NULL
              AND (
                LOWER(o.customerName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(o.orderCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(o.customerPhone) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            """)
    Page<OrderEntity> searchByKeyword(
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
            SELECT o FROM OrderEntity o
            WHERE o.tenantId = :tenantId
              AND o.status = :status
              AND o.deletedAt IS NULL
              AND (
                LOWER(o.customerName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(o.orderCode) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(o.customerPhone) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            """)
    Page<OrderEntity> searchByKeywordAndStatus(
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            Pageable pageable
    );
}
