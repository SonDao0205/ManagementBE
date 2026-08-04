package com.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.backend.entity.ShipmentEntity;

@Repository
public interface ShipmentRepository extends JpaRepository<ShipmentEntity, String> {

    Page<ShipmentEntity> findAllByTenantId(String tenantId, Pageable pageable);

    List<ShipmentEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<ShipmentEntity> findFirstByTenantIdAndTrackingNumberIgnoreCase(
            String tenantId, String trackingNumber);

    Optional<ShipmentEntity> findFirstByTenantIdAndOrderIdOrderByCreatedAtDesc(
            String tenantId, String orderId);

    @Query(value = """
            SELECT s.* FROM shipments s
            JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id
            WHERE s.tenant_id = :tenantId
              AND (
                LOWER(COALESCE(s.tracking_number, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(s.shipping_provider, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(s.external_package_id) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(o.external_order_id) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            ORDER BY s.created_at DESC
            """, countQuery = """
            SELECT COUNT(*) FROM shipments s
            JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id
            WHERE s.tenant_id = :tenantId
              AND (
                LOWER(COALESCE(s.tracking_number, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(s.shipping_provider, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(s.external_package_id) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(o.external_order_id) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            """, nativeQuery = true)
    Page<ShipmentEntity> search(
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query(value = """
            SELECT s.* FROM shipments s
            JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id
            WHERE s.tenant_id = :tenantId
              AND (
                LOWER(COALESCE(s.tracking_number, '')) = LOWER(:code)
                OR LOWER(s.external_package_id) = LOWER(:code)
                OR LOWER(o.external_order_id) = LOWER(:code)
                OR s.order_id = :code
              )
            ORDER BY s.created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ShipmentEntity> track(
            @Param("tenantId") String tenantId,
            @Param("code") String code);
}
