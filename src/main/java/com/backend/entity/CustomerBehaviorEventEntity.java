package com.backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@IdClass(CustomerBehaviorEventPK.class)
@Table(name = "customer_behavior_events")
public class CustomerBehaviorEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Id
    @Column(name = "marketplace_account_id", nullable = false, length = 36)
    private String marketplaceAccountId;

    @Column(name = "tenant_id", nullable = false, insertable = false, updatable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "marketplace_account_id", nullable = false, insertable = false, updatable = false)
    private MarketplaceAccountEntity marketplaceAccount;

    @Column(name = "marketplace_customer_id", nullable = false, insertable = false, updatable = false)
    private String marketplaceCustomerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "marketplace_customer_id", nullable = false)
    private MarketplaceCustomerEntity marketplaceCustomer;

    @Column(name = "source_session_id", length = 100)
    private String sourceSessionId;

    @Column(name = "marketplace_code", nullable = false, length = 30)
    private String marketplaceCode;

    @Column(name = "event_name", nullable = false, length = 50)
    private String eventName;

    @Column(length = 100)
    private String screen;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_external_id", length = 200)
    private String entityExternalId;

    @Column(name = "properties_json", columnDefinition = "jsonb", nullable = false)
    private String propertiesJson = "{}";

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();
}
