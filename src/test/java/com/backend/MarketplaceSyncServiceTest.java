package com.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.backend.dto.MarketplaceSyncResponse;
import com.backend.entity.MarketplaceAccountEntity;
import com.backend.entity.MarketplaceCredentialEntity;
import com.backend.entity.MarketplaceEntity;
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
import com.backend.service.MarketplaceSyncService;

class MarketplaceSyncServiceTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void keepsPullAndPushIndependentForEveryShop() throws Exception {
        MarketplaceAccountRepository accountRepository = mock(MarketplaceAccountRepository.class);
        MarketplaceCredentialRepository credentialRepository =
                mock(MarketplaceCredentialRepository.class);
        MarketplaceRepository marketplaceRepository = mock(MarketplaceRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductVariantRepository variantRepository = mock(ProductVariantRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
        CredentialEncryptionService encryptionService = mock(CredentialEncryptionService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        MarketplaceConnector connector = mock(MarketplaceConnector.class);
        when(connector.marketplaceCode()).thenReturn("TIKTOK_SHOP");
        MarketplaceConnectorRegistry connectorRegistry =
                new MarketplaceConnectorRegistry(List.of(connector));
        MarketplaceEntity marketplace = new MarketplaceEntity();
        marketplace.setId("marketplace-tiktok");
        marketplace.setMarketplaceCode("TIKTOK_SHOP");
        when(marketplaceRepository.findById("marketplace-tiktok"))
                .thenReturn(Optional.of(marketplace));

        MarketplaceAccountEntity shopA = account("account-a", "SHOP-A", "Shop A");
        MarketplaceAccountEntity shopB = account("account-b", "SHOP-B", "Shop B");
        when(accountRepository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc("tenant-1"))
                .thenReturn(List.of(shopA, shopB));
        credential("account-a", "encrypted-a", credentialRepository);
        credential("account-b", "encrypted-b", credentialRepository);
        when(encryptionService.decrypt("encrypted-a")).thenReturn("token-a");
        when(encryptionService.decrypt("encrypted-b")).thenReturn("token-b");
        when(connector.getShopProfile("token-a")).thenReturn(profile("SHOP-A", "Shop A"));
        when(connector.getShopProfile("token-b")).thenReturn(profile("SHOP-B", "Shop B"));

        when(connector.getProducts("token-a"))
                .thenThrow(new IllegalStateException("pull-a-failed"));
        when(connector.getProducts("token-b")).thenReturn(List.of());
        when(connector.getOrders("token-b")).thenReturn(List.of());

        ProductEntity product = new ProductEntity();
        product.setId("product-1");
        product.setTenantId("tenant-1");
        product.setName("Sản phẩm kiểm thử");
        product.setDescription("Mô tả");
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId("variant-1");
        variant.setTenantId("tenant-1");
        variant.setProductId("product-1");
        variant.setSku("SKU-1");
        variant.setPrice(BigDecimal.valueOf(125_000));
        variant.setStockQuantity(12);
        when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                "product-1", "tenant-1")).thenReturn(Optional.of(product));
        when(variantRepository
                .findAllByProductIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                        "product-1", "tenant-1"))
                .thenReturn(List.of(variant));

        MarketplaceConnector.ProductPublishResult published =
                new MarketplaceConnector.ProductPublishResult(
                        "platform-product-1",
                        List.of(new MarketplaceConnector.ProductVariantPublishResult(
                                "variant-1", "platform-sku-1", "SKU-1")));
        when(connector.publishProduct(eq("token-a"), any())).thenReturn(published);
        when(connector.publishProduct(eq("token-b"), any()))
                .thenThrow(new IllegalStateException("push-b-failed"));

        JdbcClient jdbcClient = jdbcWithPendingProduct();
        MarketplaceSyncService service = new MarketplaceSyncService(
                accountRepository,
                credentialRepository,
                marketplaceRepository,
                productRepository,
                variantRepository,
                orderRepository,
                orderItemRepository,
                connectorRegistry,
                encryptionService,
                jdbcClient,
                transactionManager);

        MarketplaceSyncResponse response = service.syncTenant("tenant-1");

        assertThat(response.accounts()).isEqualTo(2);
        assertThat(response.pushedProducts()).isEqualTo(1);
        assertThat(response.pullFailures()).isEqualTo(1);
        assertThat(response.pushFailures()).isEqualTo(1);
        assertThat(response.shopResults())
                .filteredOn(result -> result.externalAccountId().equals("SHOP-A"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.pullStatus()).isEqualTo("FAILED");
                    assertThat(result.pushStatus()).isEqualTo("SUCCEEDED");
                    assertThat(result.status()).isEqualTo("PARTIAL");
                });
        assertThat(response.shopResults())
                .filteredOn(result -> result.externalAccountId().equals("SHOP-B"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.pullStatus()).isEqualTo("SUCCEEDED");
                    assertThat(result.pushStatus()).isEqualTo("FAILED");
                    assertThat(result.status()).isEqualTo("PARTIAL");
                });
        verify(connector).publishProduct(eq("token-a"), any());
        verify(connector).publishProduct(eq("token-b"), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void syncsMultipleShopsIndependentlyAndRejectsTokenFromAnotherShop() {
        MarketplaceAccountRepository accountRepository = mock(MarketplaceAccountRepository.class);
        MarketplaceCredentialRepository credentialRepository =
                mock(MarketplaceCredentialRepository.class);
        MarketplaceRepository marketplaceRepository = mock(MarketplaceRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductVariantRepository variantRepository = mock(ProductVariantRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
        CredentialEncryptionService encryptionService = mock(CredentialEncryptionService.class);
        JdbcClient jdbcClient = mock(JdbcClient.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);

        MarketplaceConnector connector = mock(MarketplaceConnector.class);
        when(connector.marketplaceCode()).thenReturn("TIKTOK_SHOP");
        MarketplaceConnectorRegistry connectorRegistry =
                new MarketplaceConnectorRegistry(List.of(connector));

        MarketplaceEntity marketplace = new MarketplaceEntity();
        marketplace.setId("marketplace-tiktok");
        marketplace.setMarketplaceCode("TIKTOK_SHOP");

        MarketplaceAccountEntity shopA = account("account-a", "SHOP-A", "Shop A");
        MarketplaceAccountEntity shopB = account("account-b", "SHOP-B", "Shop B");
        MarketplaceAccountEntity shopC = account("account-c", "SHOP-C", "Shop C");
        when(accountRepository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc("tenant-1"))
                .thenReturn(List.of(shopA, shopB, shopC));
        when(marketplaceRepository.findById("marketplace-tiktok"))
                .thenReturn(Optional.of(marketplace));

        credential("account-a", "encrypted-a", credentialRepository);
        credential("account-b", "encrypted-b", credentialRepository);
        credential("account-c", "encrypted-c", credentialRepository);
        when(encryptionService.decrypt("encrypted-a")).thenReturn("token-a");
        when(encryptionService.decrypt("encrypted-b")).thenReturn("token-b");
        when(encryptionService.decrypt("encrypted-c")).thenReturn("token-c");

        when(connector.getShopProfile("token-a")).thenReturn(profile("SHOP-A", "Shop A"));
        when(connector.getShopProfile("token-b")).thenReturn(profile("SHOP-B", "Shop B"));
        when(connector.getShopProfile("token-c"))
                .thenReturn(profile("ANOTHER-SHOP", "Another Shop"));
        when(connector.getProducts(anyString())).thenReturn(List.of());
        when(connector.getOrders(anyString())).thenReturn(List.of());

        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec mappedQuery = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(mappedQuery);
        when(mappedQuery.list()).thenReturn(List.of());
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        MarketplaceSyncService service = new MarketplaceSyncService(
                accountRepository,
                credentialRepository,
                marketplaceRepository,
                productRepository,
                variantRepository,
                orderRepository,
                orderItemRepository,
                connectorRegistry,
                encryptionService,
                jdbcClient,
                transactionManager);

        MarketplaceSyncResponse response = service.syncTenant("tenant-1");

        assertThat(response.accounts()).isEqualTo(2);
        assertThat(response.failures()).isEqualTo(1);
        assertThat(response.shopResults()).hasSize(3);
        assertThat(response.shopResults())
                .filteredOn(result -> result.status().equals("SUCCEEDED"))
                .extracting(result -> result.externalAccountId())
                .containsExactly("SHOP-A", "SHOP-B");
        assertThat(response.shopResults())
                .filteredOn(result -> result.status().equals("FAILED"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.externalAccountId()).isEqualTo("SHOP-C");
                    assertThat(result.errorCode()).isEqualTo("MARKETPLACE_SYNC_FAILED");
                });
        assertThat(shopC.getConnectionStatus()).isEqualTo("ERROR");
    }

    private static MarketplaceAccountEntity account(
            String accountId,
            String externalAccountId,
            String shopName) {
        MarketplaceAccountEntity account = new MarketplaceAccountEntity();
        account.setId(accountId);
        account.setTenantId("tenant-1");
        account.setMarketplaceId("marketplace-tiktok");
        account.setExternalAccountId(externalAccountId);
        account.setExternalShopName(shopName);
        account.setConnectionStatus("CONNECTED");
        return account;
    }

    private static void credential(
            String accountId,
            String encryptedToken,
            MarketplaceCredentialRepository repository) {
        MarketplaceCredentialEntity credential = new MarketplaceCredentialEntity();
        credential.setMarketplaceAccountId(accountId);
        credential.setAccessTokenEncrypted(encryptedToken);
        credential.setAccessTokenExpiresAt(Instant.now().plusSeconds(3600));
        when(repository.findByMarketplaceAccountId(accountId))
                .thenReturn(Optional.of(credential));
    }

    private static MarketplaceConnector.ShopProfile profile(
            String externalAccountId,
            String shopName) {
        return new MarketplaceConnector.ShopProfile(
                externalAccountId,
                "cipher-" + externalAccountId,
                shopName,
                "VN",
                "VND",
                "Asia/Ho_Chi_Minh");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JdbcClient jdbcWithPendingProduct() throws Exception {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        when(jdbcClient.sql(anyString())).thenAnswer(sqlInvocation -> {
            String sql = sqlInvocation.getArgument(0);
            JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
            when(statement.param(anyString(), any())).thenReturn(statement);
            when(statement.update()).thenReturn(1);
            when(statement.query(any(RowMapper.class))).thenAnswer(queryInvocation -> {
                RowMapper rowMapper = queryInvocation.getArgument(0);
                JdbcClient.MappedQuerySpec mapped = mock(JdbcClient.MappedQuerySpec.class);
                if (sql.contains("sync_status IN ('PENDING', 'ERROR')")) {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("id")).thenReturn("mapping-1");
                    when(resultSet.getString("product_id")).thenReturn("product-1");
                    Object link = rowMapper.mapRow(resultSet, 0);
                    when(mapped.list()).thenReturn(List.of(link));
                } else {
                    when(mapped.list()).thenReturn(List.of());
                }
                return mapped;
            });
            return statement;
        });
        return jdbcClient;
    }
}
