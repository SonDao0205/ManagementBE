package com.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.ProductResponse;
import com.backend.dto.ProductResponse.ProductVariantResponse;
import com.backend.dto.ProductStockAdjustmentRequest;
import com.backend.dto.ProductSyncResponse;
import com.backend.dto.ProductSyncResponse.ProductSyncItemResponse;
import com.backend.dto.ProductUpsertRequest;
import com.backend.dto.ProductUpsertRequest.ProductVariantRequest;
import com.backend.entity.MarketplaceAccountEntity;
import com.backend.entity.MarketplaceCredentialEntity;
import com.backend.entity.MarketplaceEntity;
import com.backend.entity.MarketplaceProductEntity;
import com.backend.entity.MarketplaceProductVariantEntity;
import com.backend.entity.ProductEntity;
import com.backend.entity.ProductMediaEntity;
import com.backend.entity.ProductVariantEntity;
import com.backend.marketplace.MarketplaceConnector;
import com.backend.marketplace.MarketplaceConnector.MarketplaceProductPayload;
import com.backend.marketplace.MarketplaceConnector.MarketplaceProductVariantPayload;
import com.backend.marketplace.MarketplaceConnector.ProductPayload;
import com.backend.marketplace.MarketplaceConnector.ProductResult;
import com.backend.marketplace.MarketplaceConnector.ProductVariantPayload;
import com.backend.marketplace.MarketplaceConnector.ProductVariantResult;
import com.backend.marketplace.MarketplaceConnectorRegistry;
import com.backend.repository.MarketplaceAccountRepository;
import com.backend.repository.MarketplaceCredentialRepository;
import com.backend.repository.MarketplaceProductRepository;
import com.backend.repository.MarketplaceProductVariantRepository;
import com.backend.repository.MarketplaceRepository;
import com.backend.repository.ProductMediaRepository;
import com.backend.repository.ProductRepository;
import com.backend.repository.ProductVariantRepository;
import com.backend.security.CredentialEncryptionService;
import com.backend.security.TenantPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional
public class ProductService {

    private static final Set<String> PRODUCT_STATUSES =
            Set.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED");

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductMediaRepository productMediaRepository;
    private final MarketplaceProductRepository marketplaceProductRepository;
    private final MarketplaceProductVariantRepository marketplaceProductVariantRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final MarketplaceCredentialRepository marketplaceCredentialRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final MarketplaceConnectorRegistry connectorRegistry;
    private final MarketplaceConnectionService marketplaceConnectionService;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductService(
            ProductRepository productRepository,
            ProductVariantRepository productVariantRepository,
            ProductMediaRepository productMediaRepository,
            MarketplaceProductRepository marketplaceProductRepository,
            MarketplaceProductVariantRepository marketplaceProductVariantRepository,
            MarketplaceAccountRepository marketplaceAccountRepository,
            MarketplaceCredentialRepository marketplaceCredentialRepository,
            MarketplaceRepository marketplaceRepository,
            MarketplaceConnectorRegistry connectorRegistry,
            MarketplaceConnectionService marketplaceConnectionService,
            CredentialEncryptionService encryptionService) {
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.productMediaRepository = productMediaRepository;
        this.marketplaceProductRepository = marketplaceProductRepository;
        this.marketplaceProductVariantRepository = marketplaceProductVariantRepository;
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.marketplaceCredentialRepository = marketplaceCredentialRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.connectorRegistry = connectorRegistry;
        this.marketplaceConnectionService = marketplaceConnectionService;
        this.encryptionService = encryptionService;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(
            String tenantId,
            String search,
            String status,
            Pageable pageable) {
        String normalizedSearch = blankToNull(search);
        String normalizedStatus = normalizeListStatus(status);
        return productRepository.search(
                        tenantId,
                        normalizedSearch,
                        normalizedStatus,
                        pageable)
                .map(this::toResponse);
    }

    public ProductResponse create(
            TenantPrincipal principal,
            ProductUpsertRequest request) {
        String productCode = request.productCode().trim();
        if (productRepository.existsByTenantIdAndProductCodeAndDeletedAtIsNull(
                principal.tenantId(),
                productCode)) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "PRODUCT_CODE_EXISTS",
                    "Mã sản phẩm đã tồn tại.");
        }

        Instant now = Instant.now();
        ProductEntity product = new ProductEntity();
        product.setId(UUID.randomUUID().toString());
        product.setTenantId(principal.tenantId());
        product.setProductCode(productCode);
        product.setCreatedByUserId(principal.userId());
        product.setCreatedAt(now);
        product.setVersion(1);
        applyProduct(product, request, now);
        productRepository.save(product);
        saveVariants(product, request, now);
        saveImage(product, request.imageUrl(), now);

        Set<String> requestedMarketplaces = normalizeMarketplaceCodes(
                request.marketplaces());
        if (!requestedMarketplaces.isEmpty()) {
            syncProducts(
                    principal,
                    List.of(product),
                    requestedMarketplaces,
                    false);
        }
        return toResponse(product);
    }

    public ProductResponse update(
            TenantPrincipal principal,
            String productId,
            ProductUpsertRequest request) {
        ProductEntity product = requireProduct(principal.tenantId(), productId);
        String productCode = request.productCode().trim();
        if (!product.getProductCode().equals(productCode)
                && productRepository.existsByTenantIdAndProductCodeAndDeletedAtIsNull(
                        principal.tenantId(),
                        productCode)) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "PRODUCT_CODE_EXISTS",
                    "Mã sản phẩm đã tồn tại.");
        }

        product.setProductCode(productCode);
        product.setVersion(product.getVersion() + 1);
        applyProduct(product, request, Instant.now());
        productRepository.save(product);
        saveVariants(product, request, Instant.now());
        saveImage(product, request.imageUrl(), Instant.now());

        Set<String> targets = normalizeMarketplaceCodes(request.marketplaces());
        if (targets.isEmpty()) {
            targets = marketplaceCodesForProduct(product);
        }
        if (!targets.isEmpty()) {
            syncProducts(principal, List.of(product), targets, false);
        }
        return toResponse(product);
    }

    public void delete(String tenantId, String productId) {
        ProductEntity product = requireProduct(tenantId, productId);
        Instant now = Instant.now();
        product.setStatus("ARCHIVED");
        product.setDeletedAt(now);
        product.setUpdatedAt(now);
        product.setVersion(product.getVersion() + 1);
        productRepository.save(product);
        productVariantRepository
                .findByTenantIdAndProductIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                        tenantId,
                        productId)
                .forEach(variant -> {
                    variant.setStatus("ARCHIVED");
                    variant.setDeletedAt(now);
                    variant.setUpdatedAt(now);
                    productVariantRepository.save(variant);
                });
    }

    public ProductResponse adjustStock(
            String tenantId,
            String productId,
            ProductStockAdjustmentRequest request) {
        ProductEntity product = requireProduct(tenantId, productId);
        List<ProductVariantEntity> variants = variants(product);
        if (variants.isEmpty()) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "PRODUCT_HAS_NO_VARIANT",
                    "Sản phẩm chưa có SKU để điều chỉnh tồn kho.");
        }
        ProductVariantEntity variant = variants.get(0);
        if (request.variantId() != null && !request.variantId().isBlank()) {
            variant = variants.stream()
                    .filter(item -> item.getId().equals(request.variantId().trim()))
                    .findFirst()
                    .orElseThrow(() -> problem(
                            HttpStatus.NOT_FOUND,
                            "PRODUCT_VARIANT_NOT_FOUND",
                            "Không tìm thấy SKU thuộc sản phẩm này."));
        }
        int newStock = variant.getStockOnHand() + request.delta();
        if (newStock < 0) {
            throw problem(
                    HttpStatus.BAD_REQUEST,
                    "INSUFFICIENT_STOCK",
                    "Số lượng tồn kho không thể nhỏ hơn 0.");
        }
        variant.setStockOnHand(newStock);
        variant.setVersion(variant.getVersion() + 1);
        variant.setUpdatedAt(Instant.now());
        productVariantRepository.save(variant);
        product.setUpdatedAt(Instant.now());
        product.setVersion(product.getVersion() + 1);
        productRepository.save(product);
        return toResponse(product);
    }

    public ProductSyncResponse syncAll(TenantPrincipal principal) {
        List<MarketplaceAccountEntity> accounts = connectedAccounts(
                principal.tenantId(),
                Set.of());
        if (accounts.isEmpty()) {
            throw problem(
                    HttpStatus.BAD_REQUEST,
                    "NO_CONNECTED_MARKETPLACE",
                    "Chưa có TikTok Shop hoặc Lazada nào được kết nối.");
        }

        int importedCount = 0;
        List<ProductSyncItemResponse> pullResults = new ArrayList<>();
        for (MarketplaceAccountEntity account : accounts) {
            MarketplaceEntity marketplace =
                    requireMarketplace(account.getMarketplaceId());
            try {
                MarketplaceConnector connector =
                        connectorRegistry.require(marketplace.getMarketplaceCode());
                List<MarketplaceProductPayload> marketplaceProducts =
                        connector.getProducts(accessToken(principal, account));
                for (MarketplaceProductPayload marketplaceProduct
                        : marketplaceProducts) {
                    boolean imported = importMarketplaceProduct(
                            principal,
                            account,
                            marketplace,
                            marketplaceProduct);
                    if (imported) {
                        importedCount++;
                    }
                }
            } catch (RuntimeException exception) {
                pullResults.add(new ProductSyncItemResponse(
                        null,
                        marketplace.getMarketplaceName(),
                        marketplace.getMarketplaceCode(),
                        account.getId(),
                        false,
                        null,
                        safeMessage(exception)));
            }
        }

        List<ProductEntity> products = productRepository
                .search(principal.tenantId(), null, null, Pageable.unpaged())
                .getContent()
                .stream()
                .filter(product -> !"DRAFT".equals(product.getStatus()))
                .toList();
        ProductSyncResponse outbound =
                syncProducts(principal, products, Set.of(), false);
        List<ProductSyncItemResponse> results = new ArrayList<>(pullResults);
        results.addAll(outbound.results());
        return new ProductSyncResponse(
                products.size(),
                accounts.size(),
                importedCount,
                outbound.successCount(),
                outbound.errorCount() + pullResults.size(),
                results);
    }

    private ProductSyncResponse syncProducts(
            TenantPrincipal principal,
            List<ProductEntity> products,
            Set<String> marketplaceCodes,
            boolean requireConnection) {
        List<MarketplaceAccountEntity> accounts = connectedAccounts(
                principal.tenantId(),
                marketplaceCodes);

        if (requireConnection && accounts.isEmpty()) {
            throw problem(
                    HttpStatus.BAD_REQUEST,
                    "NO_CONNECTED_MARKETPLACE",
                    "Chưa có TikTok Shop hoặc Lazada nào được kết nối.");
        }

        List<ProductSyncItemResponse> results = new ArrayList<>();
        for (ProductEntity product : products) {
            for (MarketplaceAccountEntity account : accounts) {
                MarketplaceEntity marketplace =
                        requireMarketplace(account.getMarketplaceId());
                try {
                    ProductResult result = pushProduct(
                            principal,
                            product,
                            account,
                            marketplace);
                    results.add(new ProductSyncItemResponse(
                            product.getId(),
                            product.getProductName(),
                            marketplace.getMarketplaceCode(),
                            account.getId(),
                            true,
                            result.externalProductId(),
                            null));
                } catch (RuntimeException exception) {
                    markSyncError(product, account);
                    results.add(new ProductSyncItemResponse(
                            product.getId(),
                            product.getProductName(),
                            marketplace.getMarketplaceCode(),
                            account.getId(),
                            false,
                            null,
                            safeMessage(exception)));
                }
            }
        }

        int successCount = (int) results.stream()
                .filter(ProductSyncItemResponse::success)
                .count();
        return new ProductSyncResponse(
                products.size(),
                accounts.size(),
                0,
                successCount,
                results.size() - successCount,
                results);
    }

    private List<MarketplaceAccountEntity> connectedAccounts(
            String tenantId,
            Set<String> marketplaceCodes) {
        return marketplaceAccountRepository
                .findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                .stream()
                .filter(account -> "CONNECTED".equals(account.getConnectionStatus()))
                .filter(account -> marketplaceCodes.isEmpty()
                        || marketplaceCodes.contains(
                                requireMarketplace(account.getMarketplaceId())
                                        .getMarketplaceCode()))
                .toList();
    }

    private boolean importMarketplaceProduct(
            TenantPrincipal principal,
            MarketplaceAccountEntity account,
            MarketplaceEntity marketplace,
            MarketplaceProductPayload source) {
        MarketplaceProductEntity existingMapping = marketplaceProductRepository
                .findByMarketplaceAccountIdAndExternalProductIdAndDeletedAtIsNull(
                        account.getId(),
                        source.externalProductId())
                .orElse(null);
        ProductEntity product = existingMapping == null
                ? findProductByMarketplaceSku(
                        principal.tenantId(),
                        account.getId(),
                        source.variants())
                : requireProduct(
                        principal.tenantId(),
                        existingMapping.getProductId());
        boolean created = product == null;
        Instant now = Instant.now();

        if (created) {
            product = new ProductEntity();
            product.setId(UUID.randomUUID().toString());
            product.setTenantId(principal.tenantId());
            product.setProductCode(uniqueProductCode(
                    principal.tenantId(),
                    source.productCode(),
                    marketplace.getMarketplaceCode(),
                    source.externalProductId()));
            product.setCreatedByUserId(principal.userId());
            product.setCreatedAt(now);
            product.setAttributesJson(json(Map.of(
                    "costPrice", BigDecimal.ZERO,
                    "minStockAlert", 5,
                    "importedFrom", marketplace.getMarketplaceCode())));
            product.setVersion(1);
        } else {
            product.setVersion(product.getVersion() + 1);
        }

        product.setProductName(source.name().trim());
        product.setDescription(blankToNull(source.description()));
        product.setInternalCategoryCode(blankToNull(source.category()));
        product.setStatus(canonicalProductStatus(source.status()));
        product.setUpdatedAt(now);
        product.setDeletedAt(null);
        productRepository.save(product);

        Map<String, ProductVariantEntity> importedVariants =
                importMarketplaceVariants(
                        product,
                        existingMapping,
                        source.variants(),
                        now);
        if (blankToNull(source.imageUrl()) != null) {
            saveImage(product, source.imageUrl(), now);
        }
        saveImportedMarketplaceMapping(
                product,
                account,
                source,
                importedVariants,
                now);
        return created;
    }

    private ProductEntity findProductByMarketplaceSku(
            String tenantId,
            String marketplaceAccountId,
            List<MarketplaceProductVariantPayload> sourceVariants) {
        for (MarketplaceProductVariantPayload sourceVariant : sourceVariants) {
            ProductVariantEntity variant = productVariantRepository
                    .findByTenantIdAndSellerSkuAndDeletedAtIsNull(
                            tenantId,
                            sourceVariant.sellerSku())
                    .orElse(null);
            if (variant == null) {
                continue;
            }
            boolean accountAlreadyMapped = marketplaceProductRepository
                    .findByProductIdAndMarketplaceAccountIdAndDeletedAtIsNull(
                            variant.getProductId(),
                            marketplaceAccountId)
                    .isPresent();
            if (!accountAlreadyMapped) {
                return requireProduct(tenantId, variant.getProductId());
            }
        }
        return null;
    }

    private Map<String, ProductVariantEntity> importMarketplaceVariants(
            ProductEntity product,
            MarketplaceProductEntity marketplaceMapping,
            List<MarketplaceProductVariantPayload> sourceVariants,
            Instant now) {
        Map<String, ProductVariantEntity> result = new HashMap<>();
        int sequence = variants(product).size() + 1;
        for (MarketplaceProductVariantPayload source : sourceVariants) {
            ProductVariantEntity variant = marketplaceMapping == null
                    ? null
                    : marketplaceProductVariantRepository
                            .findByMarketplaceProductIdAndExternalSkuIdAndDeletedAtIsNull(
                                    marketplaceMapping.getId(),
                                    source.externalSkuId())
                            .flatMap(mapping -> productVariantRepository.findById(
                                    mapping.getProductVariantId()))
                            .filter(item -> item.getDeletedAt() == null)
                            .orElse(null);
            if (variant == null) {
                variant = productVariantRepository
                        .findByTenantIdAndProductIdAndSellerSkuAndDeletedAtIsNull(
                                product.getTenantId(),
                                product.getId(),
                                source.sellerSku())
                        .orElse(null);
            }
            boolean createdVariant = variant == null;
            if (variant == null) {
                ProductVariantEntity skuOwner = productVariantRepository
                        .findByTenantIdAndSellerSkuAndDeletedAtIsNull(
                                product.getTenantId(),
                                source.sellerSku())
                        .orElse(null);
                variant = new ProductVariantEntity();
                variant.setId(UUID.randomUUID().toString());
                variant.setTenantId(product.getTenantId());
                variant.setProductId(product.getId());
                variant.setSellerSku(skuOwner == null
                        ? source.sellerSku()
                        : uniqueSellerSku(
                                product.getTenantId(),
                                source.sellerSku(),
                                source.externalSkuId()));
                variant.setCreatedAt(now);
                variant.setVersion(1);
            } else {
                variant.setVersion(variant.getVersion() + 1);
            }
            if (createdVariant) {
                variant.setVariantCode(shorten(
                        product.getProductCode() + "-" + sequence++,
                        100));
            }
            variant.setVariantName(blankToNull(source.name()));
            variant.setAttributesJson("{}");
            variant.setPrice(source.price() == null
                    ? BigDecimal.ZERO
                    : source.price().max(BigDecimal.ZERO));
            variant.setCompareAtPrice(null);
            variant.setCurrency("VND");
            variant.setStockOnHand(Math.max(0, source.stock()));
            variant.setReservedStock(0);
            variant.setStatus(canonicalVariantStatus(source.status()));
            variant.setUpdatedAt(now);
            variant.setDeletedAt(null);
            productVariantRepository.save(variant);
            result.put(source.externalSkuId(), variant);
        }
        return result;
    }

    private void saveImportedMarketplaceMapping(
            ProductEntity product,
            MarketplaceAccountEntity account,
            MarketplaceProductPayload source,
            Map<String, ProductVariantEntity> importedVariants,
            Instant now) {
        MarketplaceProductEntity mapping = marketplaceProductRepository
                .findByMarketplaceAccountIdAndExternalProductIdAndDeletedAtIsNull(
                        account.getId(),
                        source.externalProductId())
                .orElseGet(() -> {
                    MarketplaceProductEntity created =
                            new MarketplaceProductEntity();
                    created.setId(UUID.randomUUID().toString());
                    created.setTenantId(product.getTenantId());
                    created.setProductId(product.getId());
                    created.setMarketplaceAccountId(account.getId());
                    created.setCreatedAt(now);
                    return created;
                });
        mapping.setExternalProductId(source.externalProductId());
        mapping.setExternalCategoryId(blankToNull(source.category()));
        mapping.setExternalTitle(source.name());
        mapping.setRawStatus(source.status());
        mapping.setCanonicalStatus(canonicalMarketplaceStatus(source.status()));
        mapping.setSyncStatus("SYNCED");
        mapping.setExternalVersion(source.version());
        mapping.setRawPayload(json(Map.of(
                "direction", "MARKETPLACE_TO_OMNI",
                "externalProductId", source.externalProductId(),
                "productCode", source.productCode())));
        mapping.setLastSyncedAt(now);
        mapping.setUpdatedAt(now);
        mapping.setDeletedAt(null);
        marketplaceProductRepository.save(mapping);

        for (MarketplaceProductVariantPayload sourceVariant : source.variants()) {
            ProductVariantEntity variant =
                    importedVariants.get(sourceVariant.externalSkuId());
            if (variant == null) {
                continue;
            }
            MarketplaceProductVariantEntity variantMapping =
                    marketplaceProductVariantRepository
                            .findByMarketplaceProductIdAndProductVariantIdAndDeletedAtIsNull(
                                    mapping.getId(),
                                    variant.getId())
                            .orElseGet(() -> {
                                MarketplaceProductVariantEntity created =
                                        new MarketplaceProductVariantEntity();
                                created.setId(UUID.randomUUID().toString());
                                created.setTenantId(product.getTenantId());
                                created.setMarketplaceProductId(mapping.getId());
                                created.setProductVariantId(variant.getId());
                                created.setCreatedAt(now);
                                return created;
                            });
            variantMapping.setExternalSkuId(sourceVariant.externalSkuId());
            variantMapping.setExternalSellerSku(sourceVariant.sellerSku());
            variantMapping.setExternalPrice(sourceVariant.price());
            variantMapping.setExternalStock(sourceVariant.stock());
            variantMapping.setRawStatus(sourceVariant.status());
            variantMapping.setCanonicalStatus(
                    canonicalMarketplaceStatus(sourceVariant.status()));
            variantMapping.setSyncStatus("SYNCED");
            variantMapping.setRawPayload(json(Map.of(
                    "direction", "MARKETPLACE_TO_OMNI",
                    "externalSkuId", sourceVariant.externalSkuId(),
                    "sellerSku", sourceVariant.sellerSku())));
            variantMapping.setLastSyncedAt(now);
            variantMapping.setUpdatedAt(now);
            variantMapping.setDeletedAt(null);
            marketplaceProductVariantRepository.save(variantMapping);
        }
    }

    private ProductResult pushProduct(
            TenantPrincipal principal,
            ProductEntity product,
            MarketplaceAccountEntity account,
            MarketplaceEntity marketplace) {
        List<ProductVariantEntity> variants = variants(product);
        MarketplaceProductEntity existingMapping = marketplaceProductRepository
                .findByProductIdAndMarketplaceAccountIdAndDeletedAtIsNull(
                        product.getId(),
                        account.getId())
                .orElse(null);
        Map<String, String> outboundVariantIds = new HashMap<>();
        Map<String, String> localVariantIds = new HashMap<>();
        for (ProductVariantEntity variant : variants) {
            String outboundId = existingMapping == null
                    ? variant.getId()
                    : marketplaceProductVariantRepository
                            .findByMarketplaceProductIdAndProductVariantIdAndDeletedAtIsNull(
                                    existingMapping.getId(),
                                    variant.getId())
                            .map(MarketplaceProductVariantEntity::getExternalSkuId)
                            .orElse(variant.getId());
            outboundVariantIds.put(variant.getId(), outboundId);
            localVariantIds.put(outboundId, variant.getId());
        }
        String imageUrl = imageUrl(product);
        MarketplaceConnector connector =
                connectorRegistry.require(marketplace.getMarketplaceCode());
        ProductResult marketplaceResult = connector.upsertProduct(
                accessToken(principal, account),
                new ProductPayload(
                        existingMapping == null
                                ? product.getId()
                                : existingMapping.getExternalProductId(),
                        product.getProductCode(),
                        product.getProductName(),
                        product.getDescription(),
                        product.getInternalCategoryCode(),
                        product.getStatus(),
                        imageUrl,
                        variants.stream()
                                .map(variant -> new ProductVariantPayload(
                                        outboundVariantIds.get(variant.getId()),
                                        variant.getSellerSku(),
                                        variant.getVariantName(),
                                        variant.getPrice(),
                                        Math.max(
                                                0,
                                                variant.getStockOnHand()
                                                        - variant.getReservedStock())))
                                .toList()));
        ProductResult result = new ProductResult(
                marketplaceResult.externalProductId(),
                marketplaceResult.status(),
                marketplaceResult.version(),
                marketplaceResult.variants().stream()
                        .map(variant -> new ProductVariantResult(
                                localVariantIds.getOrDefault(
                                        variant.productVariantId(),
                                        variant.productVariantId()),
                                variant.externalSkuId(),
                                variant.sellerSku(),
                                variant.price(),
                                variant.stock(),
                                variant.status()))
                        .toList());
        saveMarketplaceMapping(product, account, result);
        return result;
    }

    private String accessToken(
            TenantPrincipal principal,
            MarketplaceAccountEntity account) {
        MarketplaceCredentialEntity credential =
                requireCredential(account.getId());
        if (credential.getAccessTokenExpiresAt() != null
                && credential.getAccessTokenExpiresAt()
                        .isBefore(Instant.now().plusSeconds(30))) {
            marketplaceConnectionService.refresh(principal, account.getId());
            credential = requireCredential(account.getId());
        }
        return encryptionService.decrypt(credential.getAccessTokenEncrypted());
    }

    private void saveMarketplaceMapping(
            ProductEntity product,
            MarketplaceAccountEntity account,
            ProductResult result) {
        Instant now = Instant.now();
        MarketplaceProductEntity mapping = marketplaceProductRepository
                .findByProductIdAndMarketplaceAccountIdAndDeletedAtIsNull(
                        product.getId(),
                        account.getId())
                .orElseGet(() -> {
                    MarketplaceProductEntity created =
                            new MarketplaceProductEntity();
                    created.setId(UUID.randomUUID().toString());
                    created.setTenantId(product.getTenantId());
                    created.setProductId(product.getId());
                    created.setMarketplaceAccountId(account.getId());
                    created.setCreatedAt(now);
                    return created;
                });
        mapping.setExternalProductId(result.externalProductId());
        mapping.setExternalTitle(product.getProductName());
        mapping.setRawStatus(result.status());
        mapping.setCanonicalStatus(canonicalMarketplaceStatus(result.status()));
        mapping.setSyncStatus("SYNCED");
        mapping.setExternalVersion(result.version());
        mapping.setRawPayload(json(Map.of(
                "externalProductId", result.externalProductId(),
                "status", result.status(),
                "version", result.version())));
        mapping.setLastSyncedAt(now);
        mapping.setUpdatedAt(now);
        mapping.setDeletedAt(null);
        marketplaceProductRepository.save(mapping);

        Map<String, ProductVariantEntity> variantsById = variants(product)
                .stream()
                .collect(Collectors.toMap(
                        ProductVariantEntity::getId,
                        Function.identity()));
        for (ProductVariantResult resultVariant : result.variants()) {
            ProductVariantEntity variant =
                    variantsById.get(resultVariant.productVariantId());
            if (variant == null) {
                continue;
            }
            MarketplaceProductVariantEntity variantMapping =
                    marketplaceProductVariantRepository
                            .findByMarketplaceProductIdAndProductVariantIdAndDeletedAtIsNull(
                                    mapping.getId(),
                                    variant.getId())
                            .orElseGet(() -> {
                                MarketplaceProductVariantEntity created =
                                        new MarketplaceProductVariantEntity();
                                created.setId(UUID.randomUUID().toString());
                                created.setTenantId(product.getTenantId());
                                created.setMarketplaceProductId(mapping.getId());
                                created.setProductVariantId(variant.getId());
                                created.setCreatedAt(now);
                                return created;
                            });
            variantMapping.setExternalSkuId(resultVariant.externalSkuId());
            variantMapping.setExternalSellerSku(resultVariant.sellerSku());
            variantMapping.setExternalPrice(resultVariant.price());
            variantMapping.setExternalStock(resultVariant.stock());
            variantMapping.setRawStatus(resultVariant.status());
            variantMapping.setCanonicalStatus(
                    canonicalMarketplaceStatus(resultVariant.status()));
            variantMapping.setSyncStatus("SYNCED");
            variantMapping.setRawPayload(json(Map.of(
                    "externalSkuId", resultVariant.externalSkuId(),
                    "sellerSku", resultVariant.sellerSku())));
            variantMapping.setLastSyncedAt(now);
            variantMapping.setUpdatedAt(now);
            variantMapping.setDeletedAt(null);
            marketplaceProductVariantRepository.save(variantMapping);
        }
    }

    private void markSyncError(
            ProductEntity product,
            MarketplaceAccountEntity account) {
        marketplaceProductRepository
                .findByProductIdAndMarketplaceAccountIdAndDeletedAtIsNull(
                        product.getId(),
                        account.getId())
                .ifPresent(mapping -> {
                    mapping.setSyncStatus("ERROR");
                    mapping.setUpdatedAt(Instant.now());
                    marketplaceProductRepository.save(mapping);
                });
    }

    private void applyProduct(
            ProductEntity product,
            ProductUpsertRequest request,
            Instant now) {
        product.setProductName(request.name().trim());
        product.setDescription(blankToNull(request.description()));
        product.setInternalCategoryCode(blankToNull(request.category()));
        product.setStatus(normalizeProductStatus(request.status(), request.totalStock()));
        product.setAttributesJson(json(Map.of(
                "costPrice", zero(request.costPrice()),
                "minStockAlert", request.minStockAlert() == null
                        ? 5
                        : request.minStockAlert())));
        product.setUpdatedAt(now);
        product.setDeletedAt(null);
    }

    private void saveVariants(
            ProductEntity product,
            ProductUpsertRequest request,
            Instant now) {
        List<ProductVariantRequest> requested = request.variants() == null
                ? List.of()
                : request.variants();
        if (requested.isEmpty()) {
            requested = List.of(new ProductVariantRequest(
                    product.getProductCode(),
                    product.getProductName(),
                    zero(request.price()),
                    request.totalStock() == null ? 0 : request.totalStock()));
        }

        Map<String, ProductVariantEntity> existing = variants(product)
                .stream()
                .collect(Collectors.toMap(
                        ProductVariantEntity::getSellerSku,
                        Function.identity()));
        Set<String> retainedIds = new LinkedHashSet<>();
        int sequence = 1;
        for (ProductVariantRequest item : requested) {
            String sku = item.sku().trim();
            ProductVariantEntity variant = existing.get(sku);
            if (variant == null) {
                variant = new ProductVariantEntity();
                variant.setId(UUID.randomUUID().toString());
                variant.setTenantId(product.getTenantId());
                variant.setProductId(product.getId());
                variant.setCreatedAt(now);
                variant.setVersion(1);
            } else {
                variant.setVersion(variant.getVersion() + 1);
            }
            variant.setVariantCode(product.getProductCode() + "-" + sequence++);
            variant.setSellerSku(sku);
            variant.setVariantName(blankToNull(item.variantName()));
            variant.setAttributesJson("{}");
            BigDecimal variantPrice = requested.size() == 1
                    && request.price() != null
                    ? request.price()
                    : item.price();
            variant.setPrice(zero(variantPrice));
            variant.setCompareAtPrice(null);
            variant.setCurrency("VND");
            int stockQuantity = requested.size() == 1
                    && request.totalStock() != null
                    ? request.totalStock()
                    : item.stockQuantity() == null
                            ? 0
                            : item.stockQuantity();
            variant.setStockOnHand(stockQuantity);
            variant.setReservedStock(0);
            variant.setStatus("ACTIVE");
            variant.setUpdatedAt(now);
            variant.setDeletedAt(null);
            productVariantRepository.save(variant);
            retainedIds.add(variant.getId());
        }

        existing.values().stream()
                .filter(variant -> !retainedIds.contains(variant.getId()))
                .forEach(variant -> {
                    variant.setStatus("ARCHIVED");
                    variant.setDeletedAt(now);
                    variant.setUpdatedAt(now);
                    productVariantRepository.save(variant);
                });
    }

    private void saveImage(
            ProductEntity product,
            String imageUrl,
            Instant now) {
        ProductMediaEntity existing = productMediaRepository
                .findFirstByTenantIdAndProductIdAndPrimaryTrueAndDeletedAtIsNullOrderBySortOrderAsc(
                        product.getTenantId(),
                        product.getId())
                .orElse(null);
        String normalizedUrl = blankToNull(imageUrl);
        if (normalizedUrl == null) {
            if (existing != null) {
                existing.setDeletedAt(now);
                productMediaRepository.save(existing);
            }
            return;
        }
        ProductMediaEntity media = existing == null
                ? new ProductMediaEntity()
                : existing;
        if (existing == null) {
            media.setId(UUID.randomUUID().toString());
            media.setTenantId(product.getTenantId());
            media.setProductId(product.getId());
            media.setMediaType("IMAGE");
            media.setSortOrder(0);
            media.setPrimary(true);
            media.setCreatedAt(now);
        }
        media.setStorageKey("external/" + product.getId());
        media.setPublicUrl(normalizedUrl);
        media.setDeletedAt(null);
        productMediaRepository.save(media);
    }

    private ProductResponse toResponse(ProductEntity product) {
        List<ProductVariantEntity> variants = variants(product);
        int totalStock = variants.stream()
                .mapToInt(variant -> Math.max(
                        0,
                        variant.getStockOnHand() - variant.getReservedStock()))
                .sum();
        Map<String, Object> attributes = attributes(product);
        int minStockAlert = intAttribute(attributes, "minStockAlert", 5);
        BigDecimal costPrice = decimalAttribute(
                attributes,
                "costPrice",
                BigDecimal.ZERO);
        BigDecimal price = variants.stream()
                .map(ProductVariantEntity::getPrice)
                .filter(value -> value != null)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        return new ProductResponse(
                product.getId(),
                product.getTenantId(),
                product.getProductName(),
                product.getProductCode(),
                product.getInternalCategoryCode(),
                product.getDescription(),
                price,
                costPrice,
                totalStock,
                minStockAlert,
                imageUrl(product),
                displayStatus(product.getStatus(), totalStock, minStockAlert),
                product.getCreatedAt(),
                variants.stream()
                        .map(variant -> new ProductVariantResponse(
                                variant.getId(),
                                variant.getSellerSku(),
                                variant.getVariantName(),
                                variant.getPrice(),
                                Math.max(
                                        0,
                                        variant.getStockOnHand()
                                                - variant.getReservedStock())))
                        .toList(),
                marketplaceLabelsForProduct(product));
    }

    private List<ProductVariantEntity> variants(ProductEntity product) {
        return productVariantRepository
                .findByTenantIdAndProductIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                        product.getTenantId(),
                        product.getId());
    }

    private String imageUrl(ProductEntity product) {
        return productMediaRepository
                .findFirstByTenantIdAndProductIdAndPrimaryTrueAndDeletedAtIsNullOrderBySortOrderAsc(
                        product.getTenantId(),
                        product.getId())
                .map(ProductMediaEntity::getPublicUrl)
                .orElse("");
    }

    private List<String> marketplaceLabelsForProduct(ProductEntity product) {
        return marketplaceProductRepository
                .findByTenantIdAndProductIdAndDeletedAtIsNull(
                        product.getTenantId(),
                        product.getId())
                .stream()
                .filter(mapping -> "SYNCED".equals(mapping.getSyncStatus()))
                .map(mapping -> marketplaceAccountRepository
                        .findById(mapping.getMarketplaceAccountId())
                        .orElse(null))
                .filter(account -> account != null)
                .map(account -> requireMarketplace(account.getMarketplaceId())
                        .getMarketplaceCode())
                .distinct()
                .map(ProductService::marketplaceLabel)
                .toList();
    }

    private Set<String> marketplaceCodesForProduct(ProductEntity product) {
        return marketplaceProductRepository
                .findByTenantIdAndProductIdAndDeletedAtIsNull(
                        product.getTenantId(),
                        product.getId())
                .stream()
                .map(mapping -> marketplaceAccountRepository
                        .findById(mapping.getMarketplaceAccountId())
                        .orElse(null))
                .filter(account -> account != null)
                .map(account -> requireMarketplace(account.getMarketplaceId())
                        .getMarketplaceCode())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private ProductEntity requireProduct(String tenantId, String productId) {
        return productRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId)
                .orElseThrow(() -> problem(
                        HttpStatus.NOT_FOUND,
                        "PRODUCT_NOT_FOUND",
                        "Không tìm thấy sản phẩm."));
    }

    private MarketplaceEntity requireMarketplace(String marketplaceId) {
        return marketplaceRepository.findById(marketplaceId)
                .orElseThrow(() -> problem(
                        HttpStatus.BAD_REQUEST,
                        "MARKETPLACE_NOT_FOUND",
                        "Không tìm thấy cấu hình sàn."));
    }

    private MarketplaceCredentialEntity requireCredential(String accountId) {
        return marketplaceCredentialRepository.findByMarketplaceAccountId(accountId)
                .orElseThrow(() -> problem(
                        HttpStatus.BAD_REQUEST,
                        "MARKETPLACE_CREDENTIAL_NOT_FOUND",
                        "Shop chưa có thông tin xác thực hợp lệ."));
    }

    private Map<String, Object> attributes(ProductEntity product) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = objectMapper.readValue(
                    product.getAttributesJson(),
                    Map.class);
            return value;
        } catch (JsonProcessingException exception) {
            return new HashMap<>();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize product data", exception);
        }
    }

    private String uniqueProductCode(
            String tenantId,
            String preferred,
            String marketplaceCode,
            String externalProductId) {
        String base = blankToNull(preferred);
        if (base == null) {
            base = marketplaceCode + "-" + externalProductId;
        }
        base = shorten(
                base.trim().replaceAll("[^\\p{L}\\p{N}._-]+", "-"),
                100);
        if (base.isBlank()) {
            base = "IMPORTED-PRODUCT";
        }
        String candidate = base;
        int suffix = 2;
        while (productRepository.existsByTenantIdAndProductCode(
                tenantId,
                candidate)) {
            String marker = "-" + suffix++;
            candidate = shorten(base, 100 - marker.length()) + marker;
        }
        return candidate;
    }

    private String uniqueSellerSku(
            String tenantId,
            String preferred,
            String externalSkuId) {
        String base = shorten(preferred, 160);
        String marker = "-" + shorten(externalSkuId, 30);
        String candidate = shorten(base + marker, 200);
        int suffix = 2;
        while (productVariantRepository
                .findByTenantIdAndSellerSkuAndDeletedAtIsNull(
                        tenantId,
                        candidate)
                .isPresent()) {
            String numericSuffix = "-" + suffix++;
            candidate = shorten(
                    base + marker,
                    200 - numericSuffix.length()) + numericSuffix;
        }
        return candidate;
    }

    private static String shorten(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String canonicalProductStatus(String status) {
        return switch (canonicalMarketplaceStatus(status)) {
            case "ACTIVE" -> "ACTIVE";
            case "DRAFT" -> "DRAFT";
            default -> "INACTIVE";
        };
    }

    private static String canonicalVariantStatus(String status) {
        return "ACTIVE".equals(canonicalMarketplaceStatus(status))
                ? "ACTIVE"
                : "INACTIVE";
    }

    private static String normalizeProductStatus(String status, Integer stock) {
        String normalized = status == null
                ? null
                : status.trim().toUpperCase(Locale.ROOT);
        if ("OUT_OF_STOCK".equals(normalized) || "LOW_STOCK".equals(normalized)) {
            return "ACTIVE";
        }
        if (normalized != null && PRODUCT_STATUSES.contains(normalized)) {
            return normalized;
        }
        return stock != null && stock > 0 ? "ACTIVE" : "DRAFT";
    }

    private static String normalizeListStatus(String status) {
        String normalized = blankToNull(status);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return PRODUCT_STATUSES.contains(normalized) ? normalized : null;
    }

    private static Set<String> normalizeMarketplaceCodes(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .map(value -> switch (value) {
                    case "TIKTOK", "TIKTOK SHOP", "TIKTOK_SHOP" -> "TIKTOK_SHOP";
                    case "LAZADA" -> "LAZADA";
                    default -> "";
                })
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String marketplaceLabel(String code) {
        return "TIKTOK_SHOP".equals(code) ? "TikTok Shop" : "Lazada";
    }

    private static String canonicalMarketplaceStatus(String status) {
        String normalized = status == null
                ? ""
                : status.toUpperCase(Locale.ROOT);
        if ("ACTIVE".equals(normalized) || "ACTIVATE".equals(normalized)) {
            return "ACTIVE";
        }
        if (normalized.contains("DRAFT") || normalized.contains("PENDING")) {
            return "DRAFT";
        }
        if (normalized.contains("DELETE")) {
            return "DELETED";
        }
        return "INACTIVE";
    }

    private static String displayStatus(
            String storedStatus,
            int totalStock,
            int minStockAlert) {
        if (!"ACTIVE".equals(storedStatus)) {
            return storedStatus;
        }
        if (totalStock == 0) {
            return "OUT_OF_STOCK";
        }
        if (totalStock <= minStockAlert) {
            return "LOW_STOCK";
        }
        return "ACTIVE";
    }

    private static BigDecimal decimalAttribute(
            Map<String, Object> attributes,
            String name,
            BigDecimal fallback) {
        Object value = attributes.get(name);
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int intAttribute(
            Map<String, Object> attributes,
            String name,
            int fallback) {
        Object value = attributes.get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "Đồng bộ thất bại."
                : message;
    }

    private static AuthenticationException problem(
            HttpStatus status,
            String code,
            String message) {
        return new AuthenticationException(status, code, message);
    }
}
