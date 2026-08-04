package com.backend.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "marketplace_connection_history")
public class MarketplaceConnectionHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "marketplace_account_id", nullable = false, length = 36)
    private String marketplaceAccountId;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", nullable = false, columnDefinition = "jsonb")
    private String detailsJson;

    @Column(name = "changed_by_user_id", length = 36)
    private String changedByUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
