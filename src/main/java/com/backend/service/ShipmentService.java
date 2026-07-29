package com.backend.service;

import com.backend.dto.ShipmentOverviewResponse;
import com.backend.dto.ShipmentResponse;
import com.backend.entity.ShipmentEntity;
import com.backend.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;

    public Page<ShipmentResponse> list(String tenantId, String search, Pageable pageable) {
        // Bug fix: search now actually filters by waybillCode or carrierName via DB query
        if (search != null && !search.isBlank()) {
            return shipmentRepository
                    .findAllByTenantIdAndWaybillCodeContainingIgnoreCaseOrTenantIdAndCarrierNameContainingIgnoreCase(
                            tenantId, search, tenantId, search, pageable)
                    .map(ShipmentResponse::from);
        }
        return shipmentRepository.findAllByTenantId(tenantId, pageable).map(ShipmentResponse::from);
    }

    public ShipmentResponse track(String tenantId, String code) {
        // Try waybill code first, then orderId
        ShipmentEntity shipment = shipmentRepository
                .findFirstByTenantIdAndWaybillCodeIgnoreCase(tenantId, code)
                .or(() -> shipmentRepository.findFirstByTenantIdAndOrderId(tenantId, code))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
        return ShipmentResponse.from(shipment);
    }

    public ShipmentOverviewResponse overview(String tenantId) {
        // Bug fix: DB stores milestone_type in lowercase ('waiting','picked','transit','success','failed')
        // Match DB CHECK constraint values exactly
        long countWaiting   = shipmentRepository.countByTenantIdAndMilestoneType(tenantId, "waiting");
        long countPicked    = shipmentRepository.countByTenantIdAndMilestoneType(tenantId, "picked");
        long countInTransit = shipmentRepository.countByTenantIdAndMilestoneType(tenantId, "transit");
        long countFailed    = shipmentRepository.countByTenantIdAndMilestoneType(tenantId, "failed");
        long countSuccess   = shipmentRepository.countByTenantIdAndMilestoneType(tenantId, "success");

        return new ShipmentOverviewResponse(
                countWaiting,
                countPicked,
                countInTransit,
                countFailed,
                countSuccess,
                22.5,   // GHTK average delivery hours
                28.0,   // GHN average delivery hours
                98.2,   // GHTK success rate (%)
                95.4    // GHN success rate (%)
        );
    }
}
