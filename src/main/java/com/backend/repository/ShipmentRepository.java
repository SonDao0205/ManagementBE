package com.backend.repository;

import com.backend.entity.ShipmentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShipmentRepository extends JpaRepository<ShipmentEntity, String> {

    Page<ShipmentEntity> findAllByTenantId(String tenantId, Pageable pageable);

    long countByTenantIdAndMilestoneType(String tenantId, String milestoneType);

    Optional<ShipmentEntity> findFirstByTenantIdAndWaybillCodeIgnoreCase(String tenantId, String waybillCode);

    Optional<ShipmentEntity> findFirstByTenantIdAndOrderId(String tenantId, String orderId);

    // Bug fix: search method required by ShipmentService.list()
    Page<ShipmentEntity> findAllByTenantIdAndWaybillCodeContainingIgnoreCaseOrTenantIdAndCarrierNameContainingIgnoreCase(
            String tenantId1, String waybillCode, String tenantId2, String carrierName, Pageable pageable);
}
