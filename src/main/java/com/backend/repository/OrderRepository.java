package com.backend.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.backend.entity.OrderEntity;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    interface StatusCount {
        String getStatus();
        long getTotal();
    }

    Page<OrderEntity> findAllByTenantIdAndDeletedAtIsNull(String tenantId, Pageable pageable);

    Optional<OrderEntity> findByIdAndTenantIdAndDeletedAtIsNull(String id, String tenantId);

    Optional<OrderEntity> findByMarketplaceAccountIdAndExternalOrderId(
            String marketplaceAccountId,
            String externalOrderId);

    @Query(value = """
            SELECT o.* FROM orders o
            JOIN marketplace_accounts ma ON ma.id = o.marketplace_account_id
            JOIN marketplaces m ON m.id = ma.marketplace_id
            WHERE o.external_order_id = :externalOrderId
              AND m.marketplace_code = :marketplaceCode
              AND o.deleted_at IS NULL
              AND ma.deleted_at IS NULL
            """, nativeQuery = true)
    List<OrderEntity> findForMarketplaceStatusEvent(
            @Param("marketplaceCode") String marketplaceCode,
            @Param("externalOrderId") String externalOrderId);

    Page<OrderEntity> findAllByTenantIdAndStatusAndDeletedAtIsNull(
            String tenantId, String status, Pageable pageable);

    long countByTenantIdAndStatusAndDeletedAtIsNull(String tenantId, String status);

    long countByTenantIdAndDeletedAtIsNull(String tenantId);

    @Query(value = """
            SELECT canonical_status AS status, COUNT(*) AS total
            FROM orders
            WHERE tenant_id = :tenantId AND deleted_at IS NULL
            GROUP BY canonical_status
            """, nativeQuery = true)
    List<StatusCount> countStatuses(@Param("tenantId") String tenantId);

    @Query(value = """
            SELECT o.* FROM orders o
            WHERE o.tenant_id = :tenantId
              AND o.deleted_at IS NULL
              AND (
                LOWER(o.external_order_id) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(CAST(o.shipping_address_json AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(o.buyer_note, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            ORDER BY o.external_created_at DESC NULLS LAST, o.created_at DESC, o.id DESC
            """, countQuery = """
            SELECT COUNT(*) FROM orders o
            WHERE o.tenant_id = :tenantId
              AND o.deleted_at IS NULL
              AND (
                LOWER(o.external_order_id) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(CAST(o.shipping_address_json AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(o.buyer_note, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            """, nativeQuery = true)
    Page<OrderEntity> searchByKeyword(
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query(value = """
            SELECT o.* FROM orders o
            WHERE o.tenant_id = :tenantId
              AND o.canonical_status = :status
              AND o.deleted_at IS NULL
              AND (
                LOWER(o.external_order_id) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(CAST(o.shipping_address_json AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(o.buyer_note, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            ORDER BY o.external_created_at DESC NULLS LAST, o.created_at DESC, o.id DESC
            """, countQuery = """
            SELECT COUNT(*) FROM orders o
            WHERE o.tenant_id = :tenantId
              AND o.canonical_status = :status
              AND o.deleted_at IS NULL
              AND (
                LOWER(o.external_order_id) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(CAST(o.shipping_address_json AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(o.buyer_note, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            """, nativeQuery = true)
    Page<OrderEntity> searchByKeywordAndStatus(
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            Pageable pageable);

    @Query(value = """
            SELECT m.marketplace_code
            FROM marketplace_accounts ma
            JOIN marketplaces m ON m.id = ma.marketplace_id
            WHERE ma.id = :accountId
            """, nativeQuery = true)
    Optional<String> findMarketplaceCode(@Param("accountId") String accountId);
}
