package com.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.entity.CustomerBehaviorEventEntity;
import com.backend.entity.CustomerBehaviorEventPK;

public interface CustomerBehaviorEventRepository extends JpaRepository<CustomerBehaviorEventEntity, CustomerBehaviorEventPK> {

    @Query("""
        select e from CustomerBehaviorEventEntity e
        join fetch e.marketplaceAccount ma
        where e.tenantId = :tenantId
          and e.marketplaceCustomerId in :marketplaceCustomerIds
        order by e.occurredAt desc
    """)
    List<CustomerBehaviorEventEntity> findInteractions(
            @Param("tenantId") String tenantId,
            @Param("marketplaceCustomerIds") List<String> marketplaceCustomerIds
    );
}
