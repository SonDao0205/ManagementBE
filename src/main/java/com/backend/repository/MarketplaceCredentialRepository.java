package com.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.MarketplaceCredentialEntity;

public interface MarketplaceCredentialRepository
        extends JpaRepository<MarketplaceCredentialEntity, String> {

    Optional<MarketplaceCredentialEntity> findByMarketplaceAccountId(
            String marketplaceAccountId);
}
