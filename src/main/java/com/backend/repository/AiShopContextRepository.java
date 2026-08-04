package com.backend.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.entity.AiShopContextEntity;

public interface AiShopContextRepository extends JpaRepository<AiShopContextEntity, String> {

    List<AiShopContextEntity>
            findByTenantIdAndMarketplaceAccountIdAndDeletedAtIsNullOrderByActiveDescUpdatedAtDesc(
                    String tenantId,
                    String marketplaceAccountId);

    Optional<AiShopContextEntity> findByIdAndTenantIdAndDeletedAtIsNull(
            String id,
            String tenantId);

    boolean existsByTenantIdAndMarketplaceAccountIdAndContextNameIgnoreCaseAndDeletedAtIsNull(
            String tenantId,
            String marketplaceAccountId,
            String contextName);

    boolean existsByTenantIdAndMarketplaceAccountIdAndContextNameIgnoreCaseAndIdNotAndDeletedAtIsNull(
            String tenantId,
            String marketplaceAccountId,
            String contextName,
            String id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiShopContextEntity context
               set context.active = false,
                   context.activatedByUserId = null,
                   context.activatedAt = null,
                   context.updatedAt = :updatedAt
             where context.tenantId = :tenantId
               and context.marketplaceAccountId = :marketplaceAccountId
               and context.deletedAt is null
               and context.active = true
            """)
    int deactivateAllForShop(
            @Param("tenantId") String tenantId,
            @Param("marketplaceAccountId") String marketplaceAccountId,
            @Param("updatedAt") Instant updatedAt);
}
