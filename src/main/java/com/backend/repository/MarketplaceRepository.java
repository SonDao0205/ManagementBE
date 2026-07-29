package com.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.MarketplaceEntity;

public interface MarketplaceRepository extends JpaRepository<MarketplaceEntity, String> {

    Optional<MarketplaceEntity> findByMarketplaceCodeAndActiveTrue(String marketplaceCode);
}
