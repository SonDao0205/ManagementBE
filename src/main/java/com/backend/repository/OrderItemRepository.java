package com.backend.repository;

import com.backend.entity.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItemEntity, String> {

    List<OrderItemEntity> findAllByOrderIdAndTenantIdOrderByCreatedAtAsc(
            String orderId, String tenantId);

    Optional<OrderItemEntity> findByOrderIdAndExternalOrderItemId(
            String orderId,
            String externalOrderItemId);
}
