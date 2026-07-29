package com.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.MarketplaceConnectionHistoryEntity;

public interface MarketplaceConnectionHistoryRepository
        extends JpaRepository<MarketplaceConnectionHistoryEntity, Long> {
}
