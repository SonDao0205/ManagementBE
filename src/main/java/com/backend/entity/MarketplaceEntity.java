package com.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "marketplaces")
public class MarketplaceEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "marketplace_code", nullable = false, length = 30)
    private String marketplaceCode;

    @Column(name = "marketplace_name", nullable = false, length = 100)
    private String marketplaceName;

    @Column(name = "mock_base_url", length = 500)
    private String mockBaseUrl;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
