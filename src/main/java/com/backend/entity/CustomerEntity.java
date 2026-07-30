package com.backend.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, insertable = false, updatable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "customer_code", nullable = false, length = 100)
    private String customerCode;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "phone_normalized_encrypted", columnDefinition = "TEXT")
    private String phoneNormalizedEncrypted;

    @Column(name = "email_normalized_encrypted", columnDefinition = "TEXT")
    private String emailNormalizedEncrypted;

    @Column(name = "phone_lookup_hmac", length = 64)
    private String phoneLookupHmac;

    @Column(name = "email_lookup_hmac", length = 64)
    private String emailLookupHmac;

    @Column(name = "pii_key_version", length = 30)
    private String piiKeyVersion;

    @Column(name = "identity_status", nullable = false, length = 20)
    private String identityStatus = "UNVERIFIED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "merged_into_id", length = 36, insertable = false, updatable = false)
    private String mergedIntoId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_into_id")
    private CustomerEntity mergedInto;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CustomerIdentityLinkEntity> identityLinks = new ArrayList<>();
}
