package com.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.entity.CustomerEntity;

public interface CustomerRepository extends JpaRepository<CustomerEntity, String> {

    Optional<CustomerEntity> findByIdAndTenantIdAndDeletedAtIsNull(String id, String tenantId);

    Optional<CustomerEntity> findByTenantIdAndPhoneLookupHmacAndDeletedAtIsNull(String tenantId, String phoneLookupHmac);

    Optional<CustomerEntity> findByTenantIdAndEmailLookupHmacAndDeletedAtIsNull(String tenantId, String emailLookupHmac);

    @Query("""
        select c from CustomerEntity c
        where c.tenantId = :tenantId
          and c.deletedAt is null
          and (:search is null or lower(c.displayName) like lower(concat('%', :search, '%'))
               or c.phoneLookupHmac = :phoneHmac
               or c.emailLookupHmac = :emailHmac
               or lower(c.customerCode) like lower(concat('%', :search, '%')))
          and (:status is null or c.identityStatus = :status)
    """)
    Page<CustomerEntity> searchCustomers(
            @Param("tenantId") String tenantId,
            @Param("search") String search,
            @Param("phoneHmac") String phoneHmac,
            @Param("emailHmac") String emailHmac,
            @Param("status") String status,
            Pageable pageable
    );

    @Query("""
        select c from CustomerEntity c
        where c.tenantId = :tenantId
          and c.deletedAt is null
          and lower(c.displayName) = lower(:displayName)
          and c.id <> :customerId
          and c.identityStatus <> 'MERGED'
    """)
    List<CustomerEntity> findPotentialDuplicates(
            @Param("tenantId") String tenantId,
            @Param("displayName") String displayName,
            @Param("customerId") String customerId
    );

    long countByTenantIdAndDeletedAtIsNull(String tenantId);

    long countByTenantIdAndIdentityStatusAndDeletedAtIsNull(String tenantId, String identityStatus);
}
