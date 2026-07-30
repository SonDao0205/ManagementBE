package com.backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "customer_identity_links")
public class CustomerIdentityLinkEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, insertable = false, updatable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "customer_id", nullable = false, insertable = false, updatable = false)
    private String customerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerEntity customer;

    @Column(name = "marketplace_customer_id", nullable = false, insertable = false, updatable = false)
    private String marketplaceCustomerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "marketplace_customer_id", nullable = false)
    private MarketplaceCustomerEntity marketplaceCustomer;

    @Column(name = "link_method", nullable = false, length = 30)
    private String linkMethod;

    @Column(name = "verification_status", nullable = false, length = 20)
    private String verificationStatus = "PENDING";

    @Column(name = "verified_by_user_id", length = 36, insertable = false, updatable = false)
    private String verifiedByUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by_user_id")
    private TenantUserEntity verifiedByUser;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "evidence_json", columnDefinition = "json", nullable = false)
    private String evidenceJson = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
