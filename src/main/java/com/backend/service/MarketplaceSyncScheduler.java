package com.backend.service;

import com.backend.entity.MarketplaceAccountEntity;
import com.backend.repository.MarketplaceAccountRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketplaceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceSyncScheduler.class);
    private final MarketplaceAccountRepository accountRepository;
    private final MarketplaceSyncService syncService;

    public MarketplaceSyncScheduler(
            MarketplaceAccountRepository accountRepository,
            MarketplaceSyncService syncService) {
        this.accountRepository = accountRepository;
        this.syncService = syncService;
    }

    @Scheduled(
            fixedDelayString = "${app.marketplace.sync-interval-ms:30000}",
            initialDelayString = "${app.marketplace.sync-initial-delay-ms:30000}")
    public void synchronizeConnectedAccounts() {
        List<MarketplaceAccountEntity> accounts =
                accountRepository.findByConnectionStatusAndDeletedAtIsNull("CONNECTED");
        for (MarketplaceAccountEntity account : accounts) {
            try {
                syncService.syncAccount(account.getTenantId(), account.getId());
            } catch (Exception exception) {
                log.warn(
                        "Scheduled marketplace sync failed for account {}: {}",
                        account.getId(),
                        exception.getMessage());
            }
        }
    }
}
