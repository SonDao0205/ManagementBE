package com.backend.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.dto.MarketplaceSyncResponse;
import com.backend.dto.MarketplaceSyncRequest;
import com.backend.dto.MarketplaceShopSyncResult;
import com.backend.entity.MarketplaceAccountEntity;
import com.backend.entity.MarketplaceCredentialEntity;
import com.backend.entity.MarketplaceEntity;
import com.backend.entity.OrderEntity;
import com.backend.entity.OrderItemEntity;
import com.backend.entity.ProductEntity;
import com.backend.entity.ProductVariantEntity;
import com.backend.marketplace.MarketplaceConnector;
import com.backend.marketplace.MarketplaceConnectorRegistry;
import com.backend.repository.MarketplaceAccountRepository;
import com.backend.repository.MarketplaceCredentialRepository;
import com.backend.repository.MarketplaceRepository;
import com.backend.repository.OrderItemRepository;
import com.backend.repository.OrderRepository;
import com.backend.repository.ProductRepository;
import com.backend.repository.ProductVariantRepository;
import com.backend.security.CredentialEncryptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class MarketplaceSyncService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceSyncService.class);
    private static final String CONNECTED = "CONNECTED";

    private final MarketplaceAccountRepository accountRepository;
    private final MarketplaceCredentialRepository credentialRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MarketplaceConnectorRegistry connectorRegistry;
    private final CredentialEncryptionService encryptionService;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    public MarketplaceSyncService(
            MarketplaceAccountRepository accountRepository,
            MarketplaceCredentialRepository credentialRepository,
            MarketplaceRepository marketplaceRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            MarketplaceConnectorRegistry connectorRegistry,
            CredentialEncryptionService encryptionService,
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.connectorRegistry = connectorRegistry;
        this.encryptionService = encryptionService;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public MarketplaceSyncResponse syncTenant(String tenantId) {
        return syncAccounts(accountRepository
                .findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId), null);
    }

    public MarketplaceSyncResponse syncTenant(
            String tenantId,
            MarketplaceSyncRequest request) {
        if (request.marketplaceAccountIds() == null
                || request.marketplaceAccountIds().isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MARKETPLACE_TARGET_REQUIRED",
                    "Vui lòng chọn ít nhất một shop để đồng bộ.");
        }
        List<String> accountIds = request.marketplaceAccountIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        List<MarketplaceAccountEntity> accounts = accountRepository
                .findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                .stream()
                .filter(account -> accountIds.contains(account.getId()))
                .toList();
        if (accounts.size() != accountIds.size()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_MARKETPLACE_TARGET",
                    "Shop được chọn không thuộc doanh nghiệp hiện tại.");
        }
        List<String> productIds = request.allProducts()
                ? productRepository.findAllByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId)
                        .stream().map(ProductEntity::getId).toList()
                : request.productIds() == null ? List.of() : request.productIds().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .distinct().toList();
        if (productIds.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PRODUCT_TARGET_REQUIRED",
                    "Vui lòng chọn sản phẩm hoặc chọn tất cả sản phẩm trong kho.");
        }
        ensurePublicationTargets(tenantId, productIds, accounts);
        return syncAccounts(accounts, Set.copyOf(productIds));
    }

    public MarketplaceSyncResponse syncAccount(String tenantId, String accountId) {
        MarketplaceAccountEntity account = accountRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(accountId, tenantId)
                .orElseThrow(() -> new ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "MARKETPLACE_ACCOUNT_NOT_FOUND",
                        "Không tìm thấy shop đã liên kết."));
        if (!CONNECTED.equals(account.getConnectionStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MARKETPLACE_ACCOUNT_NOT_CONNECTED",
                    "Shop chưa được kết nối hoặc access token đã hết hạn.");
        }
        return syncAccounts(List.of(account), null);
    }

    private MarketplaceSyncResponse syncAccounts(
            List<MarketplaceAccountEntity> accounts,
            Set<String> allowedProductIds) {
        SyncCount total = new SyncCount();
        for (MarketplaceAccountEntity account : accounts) {
            String marketplaceCode = "UNKNOWN";
            if (!CONNECTED.equals(account.getConnectionStatus())) {
                total.shopResults.add(shopResult(
                        account, marketplaceCode, "SKIPPED", new SyncCount(),
                        "ACCOUNT_NOT_CONNECTED",
                        "Shop chưa được kết nối hoặc access token đã hết hạn.",
                        "SKIPPED", "SKIPPED", null, null));
                continue;
            }
            ReentrantLock accountLock = accountLocks.computeIfAbsent(
                    account.getId(), ignored -> new ReentrantLock());
            if (!accountLock.tryLock()) {
                total.failures += 1;
                total.shopResults.add(shopResult(
                        account, marketplaceCode, "SKIPPED", new SyncCount(),
                        "SYNC_IN_PROGRESS",
                        "Shop đang được đồng bộ bởi một tiến trình khác.",
                        "SKIPPED", "SKIPPED", null, null));
                log.info("Marketplace sync skipped because account {} is already syncing",
                        account.getExternalAccountId());
                continue;
            }
            try {
                MarketplaceCredentialEntity credential = credentialRepository
                        .findByMarketplaceAccountId(account.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Shop chưa có access token đồng bộ."));
                MarketplaceEntity marketplace = marketplaceRepository
                        .findById(account.getMarketplaceId())
                        .orElseThrow(() -> new IllegalStateException("Không tìm thấy sàn."));
                marketplaceCode = marketplace.getMarketplaceCode();
                MarketplaceConnector connector = connectorRegistry
                        .require(marketplaceCode);
                String accessToken = validAccessToken(account, credential, connector);
                MarketplaceConnector.ShopProfile profile = connector.getShopProfile(accessToken);
                validateShopOwnership(account, profile);
                updateVerifiedShop(account, profile);

                DirectionResult push = pushToMarketplace(
                        account, connector, accessToken, allowedProductIds);
                DirectionResult pull = pullFromMarketplace(
                        account, marketplaceCode, connector, accessToken);
                SyncCount accountCount = new SyncCount();
                accountCount.add(pull.count());
                accountCount.add(push.count());
                total.add(accountCount);
                total.accounts += 1;
                boolean pullFailed = "FAILED".equals(pull.status())
                        || "PARTIAL".equals(pull.status());
                boolean pushFailed = "FAILED".equals(push.status())
                        || "PARTIAL".equals(push.status());
                boolean pullSucceeded = "SUCCEEDED".equals(pull.status())
                        || "PARTIAL".equals(pull.status());
                boolean pushSucceeded = "SUCCEEDED".equals(push.status())
                        || "PARTIAL".equals(push.status());
                if (pullFailed) total.pullFailures += 1;
                if (pushFailed) total.pushFailures += 1;
                if (pullFailed || pushFailed) total.failures += 1;
                String overallStatus = (pullFailed || pushFailed)
                        && !(pullSucceeded || pushSucceeded)
                                ? "FAILED"
                                : pullFailed || pushFailed ? "PARTIAL" : "SUCCEEDED";
                total.shopResults.add(shopResult(
                        account, marketplaceCode, overallStatus, accountCount,
                        pullFailed || pushFailed ? "MARKETPLACE_SYNC_PARTIAL" : null,
                        joinErrors(pull.errorMessage(), push.errorMessage()),
                        pull.status(), push.status(),
                        pull.errorMessage(), push.errorMessage()));
            } catch (Exception exception) {
                total.failures += 1;
                total.pullFailures += 1;
                total.pushFailures += 1;
                total.shopResults.add(shopResult(
                        account, marketplaceCode, "FAILED", new SyncCount(),
                        "MARKETPLACE_SYNC_FAILED", safeErrorMessage(exception),
                        "FAILED", "FAILED",
                        safeErrorMessage(exception), safeErrorMessage(exception)));
                log.warn("Marketplace sync failed for {}: {}",
                        account.getExternalAccountId(), exception.getMessage());
            } finally {
                accountLock.unlock();
            }
        }
        return total.response();
    }

    private void ensurePublicationTargets(
            String tenantId,
            List<String> productIds,
            List<MarketplaceAccountEntity> accounts) {
        Instant now = Instant.now();
        for (String productId : productIds) {
            productRepository.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "PRODUCT_NOT_FOUND",
                            "Có sản phẩm được chọn không thuộc doanh nghiệp hiện tại."));
            for (MarketplaceAccountEntity account : accounts) {
                if (!CONNECTED.equals(account.getConnectionStatus())) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "MARKETPLACE_TARGET_NOT_CONNECTED",
                            "Chỉ có thể đồng bộ lên shop đang kết nối.");
                }
                jdbcClient.sql("""
                        INSERT INTO marketplace_products (
                          id, tenant_id, product_id, marketplace_account_id,
                          external_product_id, external_title, raw_status,
                          canonical_status, sync_status, raw_payload,
                          created_at, updated_at, deleted_at
                        ) VALUES (
                          :id, :tenantId, :productId, :accountId,
                          :productId, NULL, 'PENDING_PUBLICATION',
                          'DRAFT', 'PENDING', CAST('{}' AS jsonb),
                          :now, :now, NULL
                        )
                        ON CONFLICT (product_id, marketplace_account_id)
                        DO UPDATE SET sync_status = 'PENDING',
                                      updated_at = EXCLUDED.updated_at,
                                      deleted_at = NULL
                        """)
                        .param("id", UUID.randomUUID().toString())
                        .param("tenantId", tenantId)
                        .param("productId", productId)
                        .param("accountId", account.getId())
                        .param("now", Timestamp.from(now))
                        .update();
            }
        }
    }

    private DirectionResult pullFromMarketplace(
            MarketplaceAccountEntity account,
            String marketplaceCode,
            MarketplaceConnector connector,
            String accessToken) {
        try {
            List<Map<String, Object>> products = connector.getProducts(accessToken);
            List<Map<String, Object>> orders = connector.getOrders(accessToken);
            SyncCount count = new SyncCount();
            List<String> errors = new ArrayList<>();
            Set<String> synchronizedProductIds = new HashSet<>();
            Map<String, List<Map<String, Object>>> groupedProducts = groupBy(products, "product_id");
            for (Map.Entry<String, List<Map<String, Object>>> entry : groupedProducts.entrySet()) {
                synchronizedProductIds.add(entry.getKey());
                try {
                    SyncCount productCount = transactionTemplate.execute(status -> {
                        SyncCount value = new SyncCount();
                        syncProduct(account, marketplaceCode, entry.getKey(), entry.getValue(), value);
                        return value;
                    });
                    if (productCount != null) count.add(productCount);
                } catch (Exception exception) {
                    errors.add("Sản phẩm " + entry.getKey() + ": " + safeErrorMessage(exception));
                    log.warn("Marketplace product pull failed for account {}, product {}: {}",
                            account.getExternalAccountId(), entry.getKey(), exception.getMessage());
                }
            }
            try {
                transactionTemplate.executeWithoutResult(status ->
                        archiveMissingProducts(account, synchronizedProductIds, count));
            } catch (Exception exception) {
                errors.add("Không thể lưu trạng thái sản phẩm thiếu trên sàn: "
                        + safeErrorMessage(exception));
            }
            Map<String, List<Map<String, Object>>> groupedOrders = groupBy(orders, "order_id");
            for (Map.Entry<String, List<Map<String, Object>>> entry : groupedOrders.entrySet()) {
                try {
                    SyncCount orderCount = transactionTemplate.execute(status -> {
                        SyncCount value = new SyncCount();
                        syncOrder(account, marketplaceCode, entry.getKey(), entry.getValue(), value);
                        return value;
                    });
                    if (orderCount != null) count.add(orderCount);
                } catch (Exception exception) {
                    errors.add("Đơn hàng " + entry.getKey() + ": " + safeErrorMessage(exception));
                    log.warn("Marketplace order pull failed for account {}, order {}: {}",
                            account.getExternalAccountId(), entry.getKey(), exception.getMessage());
                }
            }
            if (errors.isEmpty()) {
                return new DirectionResult("SUCCEEDED", count, null);
            }
            return new DirectionResult(
                    count.products + count.orders > 0 ? "PARTIAL" : "FAILED",
                    count,
                    String.join("; ", errors));
        } catch (Exception exception) {
            log.warn("Marketplace pull failed for {}: {}",
                    account.getExternalAccountId(), exception.getMessage());
            return new DirectionResult("FAILED", new SyncCount(), safeErrorMessage(exception));
        }
    }

    private DirectionResult pushToMarketplace(
            MarketplaceAccountEntity account,
            MarketplaceConnector connector,
            String accessToken,
            Set<String> allowedProductIds) {
        List<PendingProductLink> pending;
        try {
            pending = pendingProducts(account, allowedProductIds);
        } catch (Exception exception) {
            return new DirectionResult("FAILED", new SyncCount(), safeErrorMessage(exception));
        }
        if (pending.isEmpty()) {
            return new DirectionResult("SKIPPED", new SyncCount(), null);
        }

        SyncCount count = new SyncCount();
        List<String> errors = new ArrayList<>();
        for (PendingProductLink link : pending) {
            try {
                ProductEntity product = productRepository
                        .findByIdAndTenantIdAndDeletedAtIsNull(
                                link.productId(), account.getTenantId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Không tìm thấy sản phẩm cần đăng lên sàn."));
                List<ProductVariantEntity> variants = variantRepository
                        .findAllByProductIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                                product.getId(), account.getTenantId());
                if (variants.isEmpty()) {
                    throw new IllegalStateException(
                            "Sản phẩm " + product.getName() + " chưa có biến thể để đăng.");
                }
                MarketplaceConnector.ProductPublishRequest request =
                        new MarketplaceConnector.ProductPublishRequest(
                                product.getId(),
                                product.getName(),
                                product.getDescription(),
                                variants.stream().map(variant ->
                                        {
                                            Map<String, Object> attributes = productAttributes(
                                                    variant.getAttributesJson());
                                            return new MarketplaceConnector.ProductVariantPublishRequest(
                                                    variant.getId(),
                                                    variant.getVariantName(),
                                                    variant.getSku(),
                                                    nullableAttribute(attributes, "color"),
                                                    nullableAttribute(attributes, "size"),
                                                    variant.getPrice(),
                                                    Math.max(0, variant.getStockQuantity() == null
                                                            ? 0 : variant.getStockQuantity()));
                                        })
                                        .toList());
                MarketplaceConnector.ProductPublishResult result =
                        connector.publishProduct(accessToken, request);
                transactionTemplate.executeWithoutResult(status ->
                        persistPublishedProduct(account, link, variants, result));
                count.pushedProducts += 1;
                count.pushedVariants += result.variants().size();
            } catch (Exception exception) {
                errors.add(safeErrorMessage(exception));
                markPublicationError(account, link, exception);
                log.warn("Marketplace push failed for account {}, product {}: {}",
                        account.getExternalAccountId(), link.productId(), exception.getMessage());
            }
        }
        if (errors.isEmpty()) return new DirectionResult("SUCCEEDED", count, null);
        String status = count.pushedProducts > 0 ? "PARTIAL" : "FAILED";
        return new DirectionResult(status, count, String.join("; ", errors));
    }

    private MarketplaceShopSyncResult shopResult(
            MarketplaceAccountEntity account,
            String marketplaceCode,
            String status,
            SyncCount count,
            String errorCode,
            String errorMessage,
            String pullStatus,
            String pushStatus,
            String pullErrorMessage,
            String pushErrorMessage) {
        return new MarketplaceShopSyncResult(
                account.getId(),
                marketplaceCode,
                account.getExternalAccountId(),
                account.getExternalShopName(),
                status,
                pullStatus,
                pushStatus,
                count.products,
                count.variants,
                count.pushedProducts,
                count.pushedVariants,
                count.orders,
                count.orderItems,
                count.archivedProducts,
                count.archivedVariants,
                errorCode,
                errorMessage,
                pullErrorMessage,
                pushErrorMessage,
                Instant.now());
    }

    private String joinErrors(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "; " + second;
    }

    private String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Không thể đồng bộ shop. Hãy kiểm tra token và trạng thái sàn.";
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private List<PendingProductLink> pendingProducts(
            MarketplaceAccountEntity account,
            Set<String> allowedProductIds) {
        List<PendingProductLink> links = jdbcClient.sql("""
                SELECT id, product_id
                FROM marketplace_products
                WHERE tenant_id = :tenantId
                  AND marketplace_account_id = :accountId
                  AND sync_status IN ('PENDING', 'ERROR')
                  AND deleted_at IS NULL
                ORDER BY updated_at ASC
                """)
                .param("tenantId", account.getTenantId())
                .param("accountId", account.getId())
                .query((rs, rowNum) -> new PendingProductLink(
                        rs.getString("id"), rs.getString("product_id")))
                .list();
        if (allowedProductIds == null) return links;
        return links.stream()
                .filter(link -> allowedProductIds.contains(link.productId()))
                .toList();
    }

    private void persistPublishedProduct(
            MarketplaceAccountEntity account,
            PendingProductLink link,
            List<ProductVariantEntity> variants,
            MarketplaceConnector.ProductPublishResult result) {
        Instant now = Instant.now();
        jdbcClient.sql("""
                UPDATE marketplace_products
                SET external_product_id = :externalProductId,
                    raw_status = 'ACTIVE', canonical_status = 'ACTIVE',
                    sync_status = 'SYNCED', raw_payload = CAST(:rawPayload AS jsonb),
                    last_synced_at = :now, updated_at = :now, deleted_at = NULL
                WHERE id = :id AND tenant_id = :tenantId
                  AND marketplace_account_id = :accountId
                """)
                .param("externalProductId", result.productId())
                .param("rawPayload", json(result))
                .param("now", Timestamp.from(now))
                .param("id", link.marketplaceProductId())
                .param("tenantId", account.getTenantId())
                .param("accountId", account.getId())
                .update();

        Map<String, ProductVariantEntity> variantsById = variants.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProductVariantEntity::getId, value -> value));
        for (MarketplaceConnector.ProductVariantPublishResult published : result.variants()) {
            ProductVariantEntity variant = variantsById.get(published.productVariantId());
            if (variant == null) continue;
            jdbcClient.sql("""
                    INSERT INTO marketplace_product_variants (
                      id, tenant_id, marketplace_product_id, product_variant_id,
                      external_sku_id, external_seller_sku, external_price,
                      external_stock, raw_status, canonical_status, sync_status,
                      raw_payload, last_synced_at, created_at, updated_at, deleted_at
                    ) VALUES (
                      :id, :tenantId, :marketplaceProductId, :variantId,
                      :externalSkuId, :sellerSku, :price,
                      :stock, 'ACTIVE', 'ACTIVE', 'SYNCED',
                      CAST(:rawPayload AS jsonb), :now, :now, :now, NULL
                    )
                    ON CONFLICT (marketplace_product_id, product_variant_id)
                    DO UPDATE SET
                      external_sku_id = EXCLUDED.external_sku_id,
                      external_seller_sku = EXCLUDED.external_seller_sku,
                      external_price = EXCLUDED.external_price,
                      external_stock = EXCLUDED.external_stock,
                      raw_status = 'ACTIVE', canonical_status = 'ACTIVE',
                      sync_status = 'SYNCED', raw_payload = EXCLUDED.raw_payload,
                      last_synced_at = EXCLUDED.last_synced_at,
                      updated_at = EXCLUDED.updated_at, deleted_at = NULL
                    """)
                    .param("id", UUID.randomUUID().toString())
                    .param("tenantId", account.getTenantId())
                    .param("marketplaceProductId", link.marketplaceProductId())
                    .param("variantId", variant.getId())
                    .param("externalSkuId", published.skuId())
                    .param("sellerSku", published.sellerSku())
                    .param("price", variant.getPrice())
                    .param("stock", variant.getStockQuantity())
                    .param("rawPayload", json(published))
                    .param("now", Timestamp.from(now))
                    .update();
        }
    }

    private void markPublicationError(
            MarketplaceAccountEntity account,
            PendingProductLink link,
            Exception exception) {
        try {
            jdbcClient.sql("""
                    UPDATE marketplace_products
                    SET raw_status = 'PUBLICATION_FAILED', canonical_status = 'FAILED',
                        sync_status = 'ERROR', raw_payload = CAST(:rawPayload AS jsonb),
                        updated_at = :now
                    WHERE id = :id AND tenant_id = :tenantId
                      AND marketplace_account_id = :accountId
                    """)
                    .param("rawPayload", json(Map.of(
                            "error", safeErrorMessage(exception),
                            "failedAt", Instant.now().toString())))
                    .param("now", Timestamp.from(Instant.now()))
                    .param("id", link.marketplaceProductId())
                    .param("tenantId", account.getTenantId())
                    .param("accountId", account.getId())
                    .update();
        } catch (Exception persistenceException) {
            log.warn("Could not persist publication failure for product {}: {}",
                    link.productId(), persistenceException.getMessage());
        }
    }

    private String validAccessToken(
            MarketplaceAccountEntity account,
            MarketplaceCredentialEntity credential,
            MarketplaceConnector connector) {
        Instant now = Instant.now();
        if (credential.getAccessTokenExpiresAt() == null
                || credential.getAccessTokenExpiresAt().isAfter(now.plusSeconds(30))) {
            return encryptionService.decrypt(credential.getAccessTokenEncrypted());
        }
        if (credential.getRefreshTokenEncrypted() == null
                || (credential.getRefreshTokenExpiresAt() != null
                    && !credential.getRefreshTokenExpiresAt().isAfter(now))) {
            account.setConnectionStatus("EXPIRED");
            account.setUpdatedAt(now);
            accountRepository.save(account);
            throw new IllegalStateException("Access token của shop đã hết hạn.");
        }
        MarketplaceConnector.TokenResult refreshed = connector.refresh(
                encryptionService.decrypt(credential.getRefreshTokenEncrypted()));
        credential.setAccessTokenEncrypted(encryptionService.encrypt(refreshed.accessToken()));
        credential.setRefreshTokenEncrypted(encryptionService.encrypt(refreshed.refreshToken()));
        credential.setAccessTokenExpiresAt(refreshed.accessTokenExpiresAt());
        credential.setRefreshTokenExpiresAt(refreshed.refreshTokenExpiresAt());
        credential.setScopesJson(json(refreshed.scopes()));
        credential.setEncryptionKeyVersion(encryptionService.keyVersion());
        credential.setLastRefreshedAt(now);
        credential.setUpdatedAt(now);
        credentialRepository.save(credential);
        account.setExpiresAt(refreshed.accessTokenExpiresAt());
        account.setConnectionStatus(CONNECTED);
        account.setUpdatedAt(now);
        accountRepository.save(account);
        return refreshed.accessToken();
    }

    private void validateShopOwnership(
            MarketplaceAccountEntity account,
            MarketplaceConnector.ShopProfile profile) {
        if (account.getExternalAccountId().equals(profile.externalAccountId())) return;
        account.setConnectionStatus("ERROR");
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
        throw new IllegalStateException(
                "Access token không thuộc shop đang đồng bộ; đã dừng để tránh lẫn dữ liệu.");
    }

    private void updateVerifiedShop(
            MarketplaceAccountEntity account,
            MarketplaceConnector.ShopProfile profile) {
        Instant now = Instant.now();
        account.setExternalShopName(profile.shopName());
        account.setShopCipher(profile.shopCipher());
        account.setSiteId(profile.siteId());
        account.setCurrency(profile.currency());
        account.setTimezoneName(profile.timezoneName());
        account.setLastVerifiedAt(now);
        account.setUpdatedAt(now);
        accountRepository.save(account);
    }

    private SyncCount persist(
            MarketplaceAccountEntity account,
            String marketplaceCode,
            List<Map<String, Object>> productRows,
            List<Map<String, Object>> orderRows) {
        SyncCount count = new SyncCount();
        Map<String, List<Map<String, Object>>> products = groupBy(productRows, "product_id");
        for (Map.Entry<String, List<Map<String, Object>>> entry : products.entrySet()) {
            syncProduct(account, marketplaceCode, entry.getKey(), entry.getValue(), count);
        }
        archiveMissingProducts(account, products.keySet(), count);
        Map<String, List<Map<String, Object>>> orders = groupBy(orderRows, "order_id");
        for (Map.Entry<String, List<Map<String, Object>>> entry : orders.entrySet()) {
            syncOrder(account, marketplaceCode, entry.getKey(), entry.getValue(), count);
        }
        return count;
    }

    private void syncProduct(
            MarketplaceAccountEntity account,
            String marketplaceCode,
            String externalProductId,
            List<Map<String, Object>> rows,
            SyncCount count) {
        Map<String, Object> first = rows.get(0);
        Instant now = Instant.now();
        String canonicalProductId = nullableText(first, "external_product_id");
        Optional<ProductLink> existingLink = findProductLink(
                account.getTenantId(), account.getId(), externalProductId);
        if (existingLink.isEmpty() && canonicalProductId != null) {
            existingLink = findProductLinkByProductId(
                    account.getTenantId(), account.getId(), canonicalProductId);
        }
        if (existingLink
                .map(link -> "PENDING".equals(link.syncStatus())
                        || "ERROR".equals(link.syncStatus()))
                .orElse(false)) {
            return;
        }
        ProductLink link = existingLink.orElseGet(() -> new ProductLink(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                externalProductId, null, null, null));
        boolean publishedFromOmnichannel = canonicalProductId != null
                && canonicalProductId.equals(link.productId());
        ProductEntity product = productRepository.findById(link.productId()).orElseGet(() -> {
            ProductEntity value = new ProductEntity();
            value.setId(link.productId());
            value.setTenantId(account.getTenantId());
            value.setProductCode(internalCode(
                    marketplaceCode, account.getExternalAccountId(), externalProductId, 100));
            value.setVersion(0);
            value.setCreatedAt(now);
            return value;
        });
        product.setName(textOr(first, "title", externalProductId));
        product.setDescription(nullableText(first, "description"));
        if (!publishedFromOmnichannel || product.getCategory() == null) {
            product.setCategory(marketplaceCode);
        }
        product.setStatus(productStatus(text(first, "status")));
        Map<String, Object> attributes = productAttributes(product.getAttributesJson());
        attributes.put("source", marketplaceCode);
        attributes.put("marketplaceAccountId", account.getId());
        attributes.put("externalProductId", externalProductId);
        String imageUrl = text(first, "image_url");
        if (!imageUrl.isBlank()) attributes.put("imageUrl", imageUrl);
        attributes.putIfAbsent("costPrice", BigDecimal.ZERO);
        attributes.putIfAbsent("minStockAlert", 5);
        product.setAttributesJson(json(attributes));
        product.setVersion(Math.max(1, product.getVersion() + 1));
        product.setUpdatedAt(now);
        product.setDeletedAt(null);
        productRepository.saveAndFlush(product);

        String rawStatus = textOr(first, "status", "unknown");
        String canonicalStatus = marketplaceProductStatus(rawStatus);
        jdbcClient.sql("""
                INSERT INTO marketplace_products (
                  id, tenant_id, product_id, marketplace_account_id,
                  external_product_id, external_title, raw_status,
                  canonical_status, sync_status, external_version,
                  raw_payload, last_synced_at, created_at, updated_at, deleted_at
                ) VALUES (
                  :id, :tenantId, :productId, :accountId,
                  :externalProductId, :title, :rawStatus,
                  :canonicalStatus, 'SYNCED', :externalVersion,
                  CAST(:rawPayload AS jsonb), :now, :now, :now, NULL
                )
                ON CONFLICT (product_id, marketplace_account_id)
                DO UPDATE SET
                  external_product_id = EXCLUDED.external_product_id,
                  external_title = EXCLUDED.external_title,
                  raw_status = EXCLUDED.raw_status,
                  canonical_status = EXCLUDED.canonical_status,
                  sync_status = 'SYNCED',
                  external_version = EXCLUDED.external_version,
                  raw_payload = EXCLUDED.raw_payload,
                  last_synced_at = EXCLUDED.last_synced_at,
                  updated_at = EXCLUDED.updated_at,
                  deleted_at = NULL
                """)
                .param("id", link.marketplaceProductId())
                .param("tenantId", account.getTenantId())
                .param("productId", product.getId())
                .param("accountId", account.getId())
                .param("externalProductId", externalProductId)
                .param("title", product.getName())
                .param("rawStatus", rawStatus)
                .param("canonicalStatus", canonicalStatus)
                .param("externalVersion", textOr(first, "version", "1"))
                .param("rawPayload", json(rows))
                .param("now", Timestamp.from(now))
                .update();

        Set<String> synchronizedSkuIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String externalSkuId = text(row, "sku_id");
            if (externalSkuId.isBlank()) continue;
            synchronizedSkuIds.add(externalSkuId);
            syncVariant(account, product, link.marketplaceProductId(), row, now);
            count.variants += 1;
        }
        archiveMissingVariants(
                account, link.marketplaceProductId(), synchronizedSkuIds, now, count);
        String afterPayload = json(rows);
        if (existingLink.isEmpty()
                || link.deletedAt() != null
                || !jsonEquivalent(link.rawPayload(), afterPayload)) {
            recordProductSync(
                    account.getTenantId(),
                    link.marketplaceProductId(),
                    existingLink.isEmpty() ? "CREATE" : "UPDATE",
                    link.rawPayload(),
                    afterPayload,
                    now);
        }
        count.products += 1;
    }

    private void syncVariant(
            MarketplaceAccountEntity account,
            ProductEntity product,
            String marketplaceProductId,
            Map<String, Object> row,
            Instant now) {
        String externalSkuId = text(row, "sku_id");
        VariantLink link = findVariantLink(marketplaceProductId, externalSkuId)
                .orElseGet(() -> new VariantLink(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        externalSkuId, null, null));
        ProductVariantEntity variant = variantRepository.findById(link.variantId()).orElseGet(() -> {
            ProductVariantEntity value = new ProductVariantEntity();
            value.setId(link.variantId());
            value.setTenantId(account.getTenantId());
            value.setProductId(product.getId());
            value.setVariantCode(internalCode("VAR", account.getExternalAccountId(), externalSkuId, 100));
            value.setSku(internalCode(
                    "SKU", account.getExternalAccountId(),
                    textOr(row, "seller_sku", externalSkuId), 200));
            value.setVersion(0);
            value.setCreatedAt(now);
            return value;
        });
        variant.setVariantName(firstNonBlank(
                nullableText(row, "variant_name"),
                nullableText(row, "sku_name"),
                nullableText(row, "seller_sku"),
                externalSkuId));
        Map<String, Object> attributes = productAttributes(variant.getAttributesJson());
        attributes.put("externalSkuId", externalSkuId);
        Map<String, String> normalized = normalizedVariantAttributes(row);
        putIfPresent(attributes, "color", normalized.get("color"));
        putIfPresent(attributes, "size", normalized.get("size"));
        variant.setAttributesJson(json(attributes));
        variant.setPrice(decimal(row.get("price")));
        variant.setCompareAtPrice(null);
        variant.setCurrency(textOr(row, "currency", "VND"));
        variant.setStockQuantity(nonNegativeInt(row.get("quantity")));
        variant.setReservedStock(0);
        variant.setStatus("ACTIVE");
        variant.setVersion(Math.max(1, variant.getVersion() + 1));
        variant.setUpdatedAt(now);
        variant.setDeletedAt(null);
        variantRepository.saveAndFlush(variant);

        jdbcClient.sql("""
                INSERT INTO marketplace_product_variants (
                  id, tenant_id, marketplace_product_id, product_variant_id,
                  external_sku_id, external_seller_sku, external_price,
                  external_stock, raw_status, canonical_status, sync_status,
                  raw_payload, last_synced_at, created_at, updated_at, deleted_at
                ) VALUES (
                  :id, :tenantId, :marketplaceProductId, :variantId,
                  :externalSkuId, :sellerSku, :price,
                  :stock, :rawStatus, 'ACTIVE', 'SYNCED',
                  CAST(:rawPayload AS jsonb), :now, :now, :now, NULL
                )
                ON CONFLICT (marketplace_product_id, external_sku_id)
                DO UPDATE SET
                  external_seller_sku = EXCLUDED.external_seller_sku,
                  external_price = EXCLUDED.external_price,
                  external_stock = EXCLUDED.external_stock,
                  raw_status = EXCLUDED.raw_status,
                  canonical_status = EXCLUDED.canonical_status,
                  sync_status = 'SYNCED',
                  raw_payload = EXCLUDED.raw_payload,
                  last_synced_at = EXCLUDED.last_synced_at,
                  updated_at = EXCLUDED.updated_at,
                  deleted_at = NULL
                """)
                .param("id", link.marketplaceVariantId())
                .param("tenantId", account.getTenantId())
                .param("marketplaceProductId", marketplaceProductId)
                .param("variantId", variant.getId())
                .param("externalSkuId", externalSkuId)
                .param("sellerSku", textOr(row, "seller_sku", externalSkuId))
                .param("price", variant.getPrice())
                .param("stock", variant.getStockQuantity())
                .param("rawStatus", "ACTIVE")
                .param("rawPayload", json(row))
                .param("now", Timestamp.from(now))
                .update();
    }

    private void syncOrder(
            MarketplaceAccountEntity account,
            String marketplaceCode,
            String externalOrderId,
            List<Map<String, Object>> rows,
            SyncCount count) {
        Map<String, Object> first = rows.get(0);
        Instant now = Instant.now();
        Optional<OrderEntity> existingOrder = orderRepository
                .findByMarketplaceAccountIdAndExternalOrderId(account.getId(), externalOrderId);
        existingOrder.ifPresent(value -> {
            if (!account.getTenantId().equals(value.getTenantId())) {
                throw new IllegalStateException("Đơn hàng đồng bộ không thuộc tenant của shop.");
            }
        });
        String previousRawStatus = existingOrder.map(OrderEntity::getRawStatus).orElse(null);
        String previousStatus = existingOrder.map(OrderEntity::getStatus).orElse(null);
        OrderEntity order = existingOrder.orElseGet(() -> {
                    OrderEntity value = new OrderEntity();
                    value.setId(UUID.randomUUID().toString());
                    value.setTenantId(account.getTenantId());
                    value.setMarketplaceAccountId(account.getId());
                    value.setExternalOrderId(externalOrderId);
                    value.setVersion(0);
                    value.setCreatedAt(now);
                    return value;
                });
        order.setRawStatus(textOr(first, "status", "unknown"));
        order.setStatus(orderStatus(textOr(first, "canonical_status", "CREATED")));
        order.setPaymentStatus(paymentStatus(textOr(first, "payment_status", "UNPAID")));
        order.setRefundStatus(refundStatus(textOr(first, "refund_status", "NONE")));
        order.setCurrency(textOr(first, "currency", "VND"));
        order.setSubtotalAmount(decimal(first.get("subtotal")));
        order.setShippingAmount(decimal(first.get("shipping_fee")));
        order.setDiscountAmount(decimal(first.get("discount_amount")));
        order.setTaxAmount(decimal(first.get("tax_amount")));
        order.setTotalAmount(decimal(first.get("total_amount")));
        String address = jsonValue(first.get("shipping_address"));
        order.setShippingAddressJson(address);
        order.setBillingAddressJson(address);
        order.setRawPayload(json(Map.of(
                "marketplace", marketplaceCode,
                "rows", rows)));
        order.setExternalCreatedAt(instant(first.get(
                marketplaceCode.equals("TIKTOK_SHOP") ? "create_time" : "created_at"), now));
        order.setExternalUpdatedAt(instant(first.get(
                marketplaceCode.equals("TIKTOK_SHOP") ? "update_time" : "updated_at"), now));
        order.setLastSyncedAt(now);
        order.setVersion(Math.max(1, order.getVersion() + 1));
        order.setUpdatedAt(now);
        order.setDeletedAt(null);
        orderRepository.saveAndFlush(order);
        if (existingOrder.isEmpty()
                || !order.getRawStatus().equals(previousRawStatus)
                || !order.getStatus().equals(previousStatus)) {
            recordOrderStatus(
                    order,
                    previousRawStatus,
                    previousStatus,
                    order.getExternalUpdatedAt());
        }

        for (Map<String, Object> row : rows) {
            String externalItemId = text(row, "order_item_id");
            if (externalItemId.isBlank()) continue;
            OrderItemEntity item = orderItemRepository
                    .findByOrderIdAndExternalOrderItemId(order.getId(), externalItemId)
                    .orElseGet(() -> {
                        OrderItemEntity value = new OrderItemEntity();
                        value.setId(UUID.randomUUID().toString());
                        value.setTenantId(account.getTenantId());
                        value.setOrderId(order.getId());
                        value.setExternalOrderItemId(externalItemId);
                        value.setCreatedAt(now);
                        return value;
                    });
            String externalProductId = textOr(row, "external_product_id", "unknown");
            String externalSkuId = nullableText(row, "external_sku_id");
            ProductLink productLink = findProductLink(
                    account.getTenantId(), account.getId(), externalProductId).orElse(null);
            VariantLink variantLink = productLink == null || externalSkuId == null
                    ? null
                    : findVariantLink(productLink.marketplaceProductId(), externalSkuId).orElse(null);
            item.setProductId(productLink == null ? null : productLink.productId());
            item.setProductVariantId(variantLink == null ? null : variantLink.variantId());
            item.setMarketplaceProductId(
                    productLink == null ? null : productLink.marketplaceProductId());
            item.setMarketplaceProductVariantId(
                    variantLink == null ? null : variantLink.marketplaceVariantId());
            item.setExternalProductId(externalProductId);
            item.setExternalSkuId(externalSkuId);
            item.setSku(nullableText(row, "seller_sku"));
            item.setProductName(textOr(row, "product_name", externalProductId));
            item.setVariantName(nullableText(row, "variant_name"));
            item.setQuantity(Math.max(1, nonNegativeInt(row.get("quantity"))));
            item.setPrice(decimal(row.get("unit_price")));
            item.setDiscountAmount(decimal(row.get("item_discount_amount")));
            item.setPaidAmount(decimal(row.get("paid_amount")));
            item.setCurrency(order.getCurrency());
            item.setRawStatus(textOr(row, "item_raw_status", order.getRawStatus()));
            item.setStatus(orderStatus(textOr(
                    row, "item_canonical_status", order.getStatus())));
            item.setRawPayload(json(row));
            item.setUpdatedAt(now);
            orderItemRepository.save(item);
            count.orderItems += 1;
        }
        count.orders += 1;
    }

    private void recordOrderStatus(
            OrderEntity order,
            String previousRawStatus,
            String previousStatus,
            Instant occurredAt) {
        String externalEventId = internalCode(
                "POLLING",
                String.valueOf(occurredAt.toEpochMilli()),
                order.getRawStatus() + "-" + order.getStatus(),
                200);
        jdbcClient.sql("""
                INSERT INTO order_status_history (
                  id, tenant_id, order_id, from_raw_status, to_raw_status,
                  from_canonical_status, to_canonical_status, source,
                  external_event_id, reason_code, occurred_at, created_at
                ) VALUES (
                  :id, :tenantId, :orderId, :fromRawStatus, :toRawStatus,
                  :fromStatus, :toStatus, 'POLLING',
                  :externalEventId, 'MARKETPLACE_SYNC', :occurredAt, :createdAt
                )
                ON CONFLICT (order_id, external_event_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", order.getTenantId())
                .param("orderId", order.getId())
                .param("fromRawStatus", previousRawStatus)
                .param("toRawStatus", order.getRawStatus())
                .param("fromStatus", previousStatus)
                .param("toStatus", order.getStatus())
                .param("externalEventId", externalEventId)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
    }

    private Optional<ProductLink> findProductLink(
            String tenantId,
            String accountId,
            String externalProductId) {
        return jdbcClient.sql("""
                SELECT id, product_id, external_product_id, raw_payload, sync_status, deleted_at
                FROM marketplace_products
                WHERE tenant_id = :tenantId
                  AND marketplace_account_id = :accountId
                  AND external_product_id = :externalProductId
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("externalProductId", externalProductId)
                .query((rs, rowNum) -> new ProductLink(
                        rs.getString("id"),
                        rs.getString("product_id"),
                        rs.getString("external_product_id"),
                        rs.getString("raw_payload"),
                        rs.getString("sync_status"),
                        rs.getTimestamp("deleted_at") == null
                                ? null : rs.getTimestamp("deleted_at").toInstant()))
                .optional();
    }

    private Optional<ProductLink> findProductLinkByProductId(
            String tenantId,
            String accountId,
            String productId) {
        return jdbcClient.sql("""
                SELECT id, product_id, external_product_id, raw_payload, sync_status, deleted_at
                FROM marketplace_products
                WHERE tenant_id = :tenantId
                  AND marketplace_account_id = :accountId
                  AND product_id = :productId
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("productId", productId)
                .query((rs, rowNum) -> new ProductLink(
                        rs.getString("id"),
                        rs.getString("product_id"),
                        rs.getString("external_product_id"),
                        rs.getString("raw_payload"),
                        rs.getString("sync_status"),
                        rs.getTimestamp("deleted_at") == null
                                ? null : rs.getTimestamp("deleted_at").toInstant()))
                .optional();
    }

    private Optional<VariantLink> findVariantLink(
            String marketplaceProductId,
            String externalSkuId) {
        return jdbcClient.sql("""
                SELECT id, product_variant_id, external_sku_id, raw_payload, deleted_at
                FROM marketplace_product_variants
                WHERE marketplace_product_id = :marketplaceProductId
                  AND external_sku_id = :externalSkuId
                LIMIT 1
                """)
                .param("marketplaceProductId", marketplaceProductId)
                .param("externalSkuId", externalSkuId)
                .query((rs, rowNum) -> new VariantLink(
                        rs.getString("id"),
                        rs.getString("product_variant_id"),
                        rs.getString("external_sku_id"),
                        rs.getString("raw_payload"),
                        rs.getTimestamp("deleted_at") == null
                                ? null : rs.getTimestamp("deleted_at").toInstant()))
                .optional();
    }

    private void archiveMissingProducts(
            MarketplaceAccountEntity account,
            Set<String> synchronizedProductIds,
            SyncCount count) {
        List<ProductLink> links = jdbcClient.sql("""
                SELECT id, product_id, external_product_id, raw_payload, sync_status, deleted_at
                FROM marketplace_products
                WHERE tenant_id = :tenantId
                  AND marketplace_account_id = :accountId
                  AND sync_status = 'SYNCED'
                  AND deleted_at IS NULL
                """)
                .param("tenantId", account.getTenantId())
                .param("accountId", account.getId())
                .query((rs, rowNum) -> new ProductLink(
                        rs.getString("id"),
                        rs.getString("product_id"),
                        rs.getString("external_product_id"),
                        rs.getString("raw_payload"),
                        rs.getString("sync_status"),
                        null))
                .list();
        for (ProductLink link : links) {
            if (synchronizedProductIds.contains(link.externalProductId())) continue;
            archiveMarketplaceProduct(account, link, count);
        }
    }

    private void archiveMarketplaceProduct(
            MarketplaceAccountEntity account,
            ProductLink link,
            SyncCount count) {
        Instant now = Instant.now();
        List<VariantLink> variants = activeVariantLinks(
                account.getTenantId(), link.marketplaceProductId());
        jdbcClient.sql("""
                UPDATE marketplace_product_variants
                SET raw_status = 'DELETED', canonical_status = 'INACTIVE',
                    sync_status = 'SYNCED', last_synced_at = :now,
                    updated_at = :now, deleted_at = :now
                WHERE tenant_id = :tenantId
                  AND marketplace_product_id = :marketplaceProductId
                  AND deleted_at IS NULL
                """)
                .param("now", Timestamp.from(now))
                .param("tenantId", account.getTenantId())
                .param("marketplaceProductId", link.marketplaceProductId())
                .update();
        for (VariantLink variantLink : variants) {
            archiveCanonicalVariantIfUnmapped(
                    account.getTenantId(), variantLink.variantId(), now);
            count.archivedVariants += 1;
        }
        jdbcClient.sql("""
                UPDATE marketplace_products
                SET raw_status = 'DELETED', canonical_status = 'DELETED',
                    sync_status = 'SYNCED', last_synced_at = :now,
                    updated_at = :now, deleted_at = :now
                WHERE id = :id AND tenant_id = :tenantId AND deleted_at IS NULL
                """)
                .param("now", Timestamp.from(now))
                .param("id", link.marketplaceProductId())
                .param("tenantId", account.getTenantId())
                .update();
        long activeMappings = jdbcClient.sql("""
                SELECT COUNT(*) FROM marketplace_products
                WHERE tenant_id = :tenantId
                  AND product_id = :productId
                  AND deleted_at IS NULL
                """)
                .param("tenantId", account.getTenantId())
                .param("productId", link.productId())
                .query(Long.class)
                .single();
        if (activeMappings == 0) {
            productRepository.findById(link.productId()).ifPresent(product -> {
                if (!account.getTenantId().equals(product.getTenantId())) {
                    throw new IllegalStateException("Sản phẩm đồng bộ không thuộc tenant của shop.");
                }
                product.setStatus("ARCHIVED");
                product.setDeletedAt(now);
                product.setUpdatedAt(now);
                product.setVersion(Math.max(1, product.getVersion() + 1));
                productRepository.save(product);
            });
        }
        recordProductSync(
                account.getTenantId(),
                link.marketplaceProductId(),
                "DELETE",
                link.rawPayload(),
                json(Map.of("deleted", true)),
                now);
        count.archivedProducts += 1;
    }

    private void archiveMissingVariants(
            MarketplaceAccountEntity account,
            String marketplaceProductId,
            Set<String> synchronizedSkuIds,
            Instant now,
            SyncCount count) {
        for (VariantLink link : activeVariantLinks(account.getTenantId(), marketplaceProductId)) {
            if (synchronizedSkuIds.contains(link.externalSkuId())) continue;
            jdbcClient.sql("""
                    UPDATE marketplace_product_variants
                    SET raw_status = 'DELETED', canonical_status = 'INACTIVE',
                        sync_status = 'SYNCED', last_synced_at = :now,
                        updated_at = :now, deleted_at = :now
                    WHERE id = :id AND tenant_id = :tenantId AND deleted_at IS NULL
                    """)
                    .param("now", Timestamp.from(now))
                    .param("id", link.marketplaceVariantId())
                    .param("tenantId", account.getTenantId())
                    .update();
            archiveCanonicalVariantIfUnmapped(account.getTenantId(), link.variantId(), now);
            count.archivedVariants += 1;
        }
    }

    private List<VariantLink> activeVariantLinks(
            String tenantId,
            String marketplaceProductId) {
        return jdbcClient.sql("""
                SELECT id, product_variant_id, external_sku_id, raw_payload, deleted_at
                FROM marketplace_product_variants
                WHERE tenant_id = :tenantId
                  AND marketplace_product_id = :marketplaceProductId
                  AND deleted_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("marketplaceProductId", marketplaceProductId)
                .query((rs, rowNum) -> new VariantLink(
                        rs.getString("id"),
                        rs.getString("product_variant_id"),
                        rs.getString("external_sku_id"),
                        rs.getString("raw_payload"),
                        null))
                .list();
    }

    private void archiveCanonicalVariantIfUnmapped(
            String tenantId,
            String variantId,
            Instant now) {
        long activeMappings = jdbcClient.sql("""
                SELECT COUNT(*) FROM marketplace_product_variants
                WHERE tenant_id = :tenantId
                  AND product_variant_id = :variantId
                  AND deleted_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("variantId", variantId)
                .query(Long.class)
                .single();
        if (activeMappings > 0) return;
        variantRepository.findById(variantId).ifPresent(variant -> {
            if (!tenantId.equals(variant.getTenantId())) {
                throw new IllegalStateException("SKU đồng bộ không thuộc tenant của shop.");
            }
            variant.setStatus("INACTIVE");
            variant.setDeletedAt(now);
            variant.setUpdatedAt(now);
            variant.setVersion(Math.max(1, variant.getVersion() + 1));
            variantRepository.save(variant);
        });
    }

    private void recordProductSync(
            String tenantId,
            String marketplaceProductId,
            String changeType,
            String beforeJson,
            String afterJson,
            Instant occurredAt) {
        jdbcClient.sql("""
                INSERT INTO product_sync_history (
                  id, tenant_id, marketplace_product_id, direction,
                  change_type, before_json, after_json, status, occurred_at
                ) VALUES (
                  :id, :tenantId, :marketplaceProductId, 'MARKETPLACE_TO_OMNI',
                  :changeType, CAST(:beforeJson AS jsonb), CAST(:afterJson AS jsonb),
                  'SUCCEEDED', :occurredAt
                )
                """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", tenantId)
                .param("marketplaceProductId", marketplaceProductId)
                .param("changeType", changeType)
                .param("beforeJson", beforeJson == null ? "null" : beforeJson)
                .param("afterJson", afterJson == null ? "null" : afterJson)
                .param("occurredAt", Timestamp.from(occurredAt))
                .update();
    }

    private boolean jsonEquivalent(String left, String right) {
        if (left == null || right == null) return left == null && right == null;
        try {
            return objectMapper.readTree(left).equals(objectMapper.readTree(right));
        } catch (JsonProcessingException exception) {
            return left.equals(right);
        }
    }

    private static Map<String, List<Map<String, Object>>> groupBy(
            List<Map<String, Object>> rows,
            String key) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String value = text(row, key);
            if (!value.isBlank()) grouped.computeIfAbsent(value, ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể chuẩn hóa payload từ sàn.", exception);
        }
    }

    private Map<String, Object> productAttributes(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(
                    rawJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, String> normalizedVariantAttributes(Map<String, Object> row) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(result, "color", firstNonBlank(
                nullableText(row, "color"),
                nullableText(row, "colour"),
                nullableText(row, "color_name"),
                nullableText(row, "color_family")));
        putIfPresent(result, "size", firstNonBlank(
                nullableText(row, "size"),
                nullableText(row, "size_name")));
        for (String key : List.of(
                "sales_attributes_json", "sales_attributes", "variation_json",
                "variation", "attributes_json", "attributes")) {
            extractVariantAttributes(row.get(key), result);
        }
        return result;
    }

    private void extractVariantAttributes(Object raw, Map<String, String> target) {
        Object value = raw;
        if (raw instanceof String string && !string.isBlank()) {
            try {
                value = objectMapper.readValue(string, Object.class);
            } catch (JsonProcessingException ignored) {
                return;
            }
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> extractVariantAttributes(item, target));
            return;
        }
        if (!(value instanceof Map<?, ?> map)) return;
        String key = firstNonBlank(
                mapText(map, "id"), mapText(map, "name"), mapText(map, "attribute_name"));
        String attributeValue = firstNonBlank(
                mapText(map, "value_name"), mapText(map, "value"), mapText(map, "attribute_value"));
        putNormalizedAttribute(target, key, attributeValue);
        map.forEach((nestedKey, nestedValue) -> {
            String candidateKey = nestedKey == null ? "" : nestedKey.toString();
            if (nestedValue instanceof Map<?, ?> || nestedValue instanceof List<?>) {
                extractVariantAttributes(nestedValue, target);
            } else {
                putNormalizedAttribute(target, candidateKey,
                        nestedValue == null ? null : nestedValue.toString());
            }
        });
    }

    private static void putNormalizedAttribute(
            Map<String, String> target,
            String rawKey,
            String value) {
        String key = normalizeAttributeKey(rawKey);
        if (Set.of("color", "colour", "colorfamily", "mau", "mausac").contains(key)) {
            putIfPresent(target, "color", value);
        } else if (Set.of("size", "kichthuoc", "kíchthước").contains(key)) {
            putIfPresent(target, "size", value);
        }
    }

    private static String normalizeAttributeKey(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(
                value.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]", "");
    }

    private static String mapText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String nullableAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static <T> void putIfPresent(Map<String, T> map, String key, T value) {
        if (value != null && !value.toString().isBlank()) map.put(key, value);
    }

    private String jsonValue(Object value) {
        if (value == null) return "{}";
        if (value instanceof String string) {
            String trimmed = string.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        }
        return json(value);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String textOr(Map<String, Object> row, String key, String fallback) {
        String value = text(row, key);
        return value.isBlank() ? fallback : value;
    }

    private static String nullableText(Map<String, Object> row, String key) {
        String value = text(row, key);
        return value.isBlank() ? null : value;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(String.valueOf(value)).max(BigDecimal.ZERO);
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private static int nonNegativeInt(Object value) {
        return Math.max(0, decimal(value).intValue());
    }

    private static Instant instant(Object value, Instant fallback) {
        if (value instanceof Number number) {
            long timestamp = number.longValue();
            return timestamp > 10_000_000_000L
                    ? Instant.ofEpochMilli(timestamp)
                    : Instant.ofEpochSecond(timestamp);
        }
        if (value != null) {
            String text = String.valueOf(value);
            try {
                return Instant.parse(text);
            } catch (RuntimeException ignored) {
                try {
                    return OffsetDateTime.parse(text).toInstant();
                } catch (RuntimeException ignoredAgain) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static String internalCode(
            String prefix,
            String account,
            String externalId,
            int maxLength) {
        String value = (prefix + "-" + account + "-" + externalId)
                .replaceAll("[^A-Za-z0-9._-]", "-");
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String productStatus(String rawStatus) {
        String status = rawStatus == null ? "" : rawStatus.toUpperCase(Locale.ROOT);
        return switch (status) {
            case "ACTIVATE", "ACTIVE" -> "ACTIVE";
            case "DRAFT", "PENDING" -> "DRAFT";
            case "DELETED" -> "ARCHIVED";
            default -> "INACTIVE";
        };
    }

    private static String marketplaceProductStatus(String rawStatus) {
        String status = productStatus(rawStatus);
        return "ARCHIVED".equals(status) ? "DELETED" : status;
    }

    private static String orderStatus(String rawStatus) {
        String status = rawStatus == null ? "" : rawStatus.toUpperCase(Locale.ROOT);
        return switch (status) {
            case "CREATED", "CONFIRMED", "READY_TO_SHIP", "SHIPPED", "IN_TRANSIT",
                    "DELIVERED", "CANCELLED", "RETURN_REQUESTED", "RETURNED", "FAILED" -> status;
            case "AWAITING_SHIPMENT", "TOPACK" -> "CONFIRMED";
            case "PACKED" -> "READY_TO_SHIP";
            case "SHIPPING" -> "IN_TRANSIT";
            case "CANCEL" -> "CANCELLED";
            default -> "CREATED";
        };
    }

    private static String paymentStatus(String rawStatus) {
        String status = rawStatus == null ? "" : rawStatus.toUpperCase(Locale.ROOT);
        return switch (status) {
            case "PAID", "PARTIALLY_REFUNDED", "REFUNDED", "FAILED" -> status;
            default -> "UNPAID";
        };
    }

    private static String refundStatus(String rawStatus) {
        String status = rawStatus == null ? "" : rawStatus.toUpperCase(Locale.ROOT);
        return switch (status) {
            case "REQUESTED", "PROCESSING", "PARTIAL", "COMPLETED", "REJECTED" -> status;
            case "REFUNDED" -> "COMPLETED";
            case "PARTIALLY_REFUNDED" -> "PARTIAL";
            default -> "NONE";
        };
    }

    private record ProductLink(
            String marketplaceProductId,
            String productId,
            String externalProductId,
            String rawPayload,
            String syncStatus,
            Instant deletedAt) {
    }

    private record VariantLink(
            String marketplaceVariantId,
            String variantId,
            String externalSkuId,
            String rawPayload,
            Instant deletedAt) {
    }

    private record PendingProductLink(
            String marketplaceProductId,
            String productId) {
    }

    private record DirectionResult(
            String status,
            SyncCount count,
            String errorMessage) {
    }

    private static final class SyncCount {
        int accounts;
        int products;
        int variants;
        int pushedProducts;
        int pushedVariants;
        int orders;
        int orderItems;
        int archivedProducts;
        int archivedVariants;
        int failures;
        int pullFailures;
        int pushFailures;
        final List<MarketplaceShopSyncResult> shopResults = new ArrayList<>();

        void add(SyncCount other) {
            products += other.products;
            variants += other.variants;
            pushedProducts += other.pushedProducts;
            pushedVariants += other.pushedVariants;
            orders += other.orders;
            orderItems += other.orderItems;
            archivedProducts += other.archivedProducts;
            archivedVariants += other.archivedVariants;
            failures += other.failures;
        }

        MarketplaceSyncResponse response() {
            return new MarketplaceSyncResponse(
                    accounts, products, variants, pushedProducts, pushedVariants,
                    orders, orderItems,
                    archivedProducts, archivedVariants,
                    failures, pullFailures, pushFailures,
                    List.copyOf(shopResults), Instant.now());
        }
    }
}
