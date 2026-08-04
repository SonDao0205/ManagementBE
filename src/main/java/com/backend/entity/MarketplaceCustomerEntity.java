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
@Table(name = "marketplace_customers")
public class MarketplaceCustomerEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, insertable = false, updatable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "marketplace_account_id", nullable = false, insertable = false, updatable = false)
    private String marketplaceAccountId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccountEntity marketplaceAccount;

    @Column(name = "external_customer_id", nullable = false, length = 200)
    private String externalCustomerId;

    @Column(name = "external_im_user_id", length = 200)
    private String externalImUserId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "phone_masked", length = 100)
    private String phoneMasked;

    @Column(name = "email_masked", length = 255)
    private String emailMasked;

    @Column(name = "raw_payload", columnDefinition = "jsonb", nullable = false)
    private String rawPayload = "{}";

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
