package com.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.ProductRequest;
import com.backend.dto.ProductResponse;
import com.backend.dto.ProductMarketplaceSyncRequest;
import com.backend.dto.ProductMarketplaceSyncResponse;
import com.backend.dto.StockAdjustmentRequest;
import com.backend.entity.ProductEntity;
import com.backend.entity.ProductVariantEntity;
import com.backend.repository.ProductRepository;
import com.backend.repository.ProductVariantRepository;
import com.backend.repository.ProductMediaRepository;
import com.backend.repository.MarketplaceAccountRepository;
import com.backend.security.TenantPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional
public class ProductService {

    private static final Set<String> PRODUCT_STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE");
    private static final int DEFAULT_MIN_STOCK_ALERT = 5;

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductMediaRepository mediaRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductService(
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            ProductMediaRepository mediaRepository,
            MarketplaceAccountRepository marketplaceAccountRepository,
            JdbcClient jdbcClient) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.mediaRepository = mediaRepository;
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(
            String tenantId,
            String search,
            String status,
            Pageable pageable) {
        String keyword = normalize(search).toLowerCase(Locale.ROOT);
        String requestedStatus = normalize(status).toUpperCase(Locale.ROOT);

        List<ProductResponse> filtered = productRepository
                .findAllByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId)
                .stream()
                .map(this::toResponse)
                .filter(product -> keyword.isEmpty()
                        || containsIgnoreCase(product.name(), keyword)
                        || containsIgnoreCase(product.productCode(), keyword)
                        || containsIgnoreCase(product.category(), keyword)
                        || product.variants().stream()
                                .anyMatch(variant -> containsIgnoreCase(variant.sku(), keyword)
                                        || containsIgnoreCase(variant.variantName(), keyword)))
                .filter(product -> requestedStatus.isEmpty()
                        || matchesDisplayStatus(product.status(), requestedStatus))
                .toList();

        int start = Math.min((int) pageable.getOffset(), filtered.size());
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(start, end), pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(String tenantId, String id) {
        return toResponse(findOrThrow(tenantId, id));
    }

    public ProductResponse create(TenantPrincipal principal, ProductRequest request) {
        String productCode = normalizeRequired(request.productCode(), "Mã sản phẩm không được để trống.");
        ensureProductCodeAvailable(principal.tenantId(), productCode, null);

        Instant now = Instant.now();
        ProductEntity product = new ProductEntity();
        product.setId(nextId());
        product.setTenantId(principal.tenantId());
        product.setCreatedByUserId(principal.userId());
        product.setCreatedAt(now);
        product.setVersion(1);
        applyRequest(product, request, now);
        productRepository.saveAndFlush(product);

        List<ProductVariantEntity> variants = saveVariants(product, request, now);
        return toResponse(product, variants);
    }

    public ProductResponse update(
            String tenantId,
            String id,
            ProductRequest request) {
        ProductEntity product = findOrThrow(tenantId, id);
        String productCode = normalizeRequired(request.productCode(), "Mã sản phẩm không được để trống.");
        ensureProductCodeAvailable(tenantId, productCode, id);

        Instant now = Instant.now();
        applyRequest(product, request, now);
        product.setVersion(product.getVersion() + 1);
        productRepository.saveAndFlush(product);
        List<ProductVariantEntity> variants = saveVariants(product, request, now);
        return toResponse(product, variants);
    }

    public ProductMarketplaceSyncResponse queueMarketplaceSync(
            String tenantId,
            ProductMarketplaceSyncRequest request) {
        List<String> accountIds = request.marketplaceAccountIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        accountIds.forEach(accountId -> requireConnectedMarketplaceAccount(tenantId, accountId));

        List<ProductEntity> products;
        if (request.allProducts()) {
            products = productRepository
                    .findAllByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId);
        } else {
            products = request.productIds().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .map(productId -> findOrThrow(tenantId, productId))
                    .toList();
        }
        if (products.isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "NO_PRODUCTS_TO_SYNC",
                    "Không có sản phẩm nào trong kho để đồng bộ.");
        }

        Instant now = Instant.now();
        for (ProductEntity product : products) {
            for (String accountId : accountIds) {
                queueMarketplaceTarget(product, accountId, now);
            }
        }
        return new ProductMarketplaceSyncResponse(
                products.size(), accountIds.size(), products.size() * accountIds.size());
    }

    public void delete(String tenantId, String id) {
        ProductEntity product = findOrThrow(tenantId, id);
        Instant now = Instant.now();
        product.setStatus("ARCHIVED");
        product.setDeletedAt(now);
        product.setUpdatedAt(now);
        product.setVersion(product.getVersion() + 1);

        variantRepository.findAllByProductIdAndTenantId(id, tenantId).stream()
                .filter(variant -> variant.getDeletedAt() == null)
                .forEach(variant -> {
                    variant.setStatus("ARCHIVED");
                    variant.setDeletedAt(now);
                    variant.setUpdatedAt(now);
                    variant.setVersion(variant.getVersion() + 1);
                });
        productRepository.save(product);
    }

    public ProductResponse adjustStock(
            String tenantId,
            String id,
            StockAdjustmentRequest request) {
        ProductEntity product = findOrThrow(tenantId, id);
        List<ProductVariantEntity> variants = activeVariants(id, tenantId);
        if (variants.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_HAS_NO_VARIANT",
                    "Sản phẩm chưa có biến thể để điều chỉnh tồn kho.");
        }

        ProductVariantEntity target;
        if (request.variantId() != null && !request.variantId().isBlank()) {
            target = variants.stream()
                    .filter(variant -> variant.getId().equals(request.variantId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VARIANT_NOT_FOUND",
                            "Không tìm thấy biến thể thuộc sản phẩm này."));
        } else if (variants.size() == 1) {
            target = variants.get(0);
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "VARIANT_REQUIRED",
                    "Sản phẩm có nhiều biến thể. Vui lòng chọn biến thể cần điều chỉnh tồn kho.");
        }

        int current = defaultInt(target.getStockQuantity());
        int adjusted;
        try {
            adjusted = Math.addExact(current, request.delta());
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STOCK_QUANTITY",
                    "Số lượng điều chỉnh tồn kho vượt quá giới hạn cho phép.");
        }
        if (adjusted < defaultInt(target.getReservedStock())) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_AVAILABLE_STOCK",
                    "Tồn kho sau điều chỉnh không được nhỏ hơn số lượng đang được giữ chỗ.");
        }

        target.setStockQuantity(adjusted);
        target.setUpdatedAt(Instant.now());
        target.setVersion(target.getVersion() + 1);
        variantRepository.saveAndFlush(target);
        return toResponse(product);
    }

    private ProductEntity findOrThrow(String tenantId, String id) {
        return productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "Không tìm thấy sản phẩm."));
    }

    private void ensureProductCodeAvailable(String tenantId, String productCode, String currentId) {
        productRepository.findByTenantIdAndProductCodeIgnoreCaseAndDeletedAtIsNull(tenantId, productCode)
                .filter(existing -> !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_CODE_ALREADY_EXISTS",
                            "Mã sản phẩm đã tồn tại trong cửa hàng.");
                });
    }

    private void applyRequest(ProductEntity product, ProductRequest request, Instant now) {
        product.setName(normalizeRequired(request.name(), "Tên sản phẩm không được để trống."));
        product.setProductCode(normalizeRequired(request.productCode(), "Mã sản phẩm không được để trống."));
        product.setCategory(blankToNull(request.category()));
        product.setDescription(blankToNull(request.description()));

        String status = normalize(request.status()).toUpperCase(Locale.ROOT);
        if (status.isEmpty()) {
            status = requestedStock(request) > 0 ? "ACTIVE" : "DRAFT";
        }
        if (!PRODUCT_STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_STATUS",
                    "Trạng thái sản phẩm không hợp lệ.");
        }
        product.setStatus(status);

        Map<String, Object> attributes = readAttributes(product.getAttributesJson());
        attributes.put("costPrice", nonNegative(request.costPrice()));
        attributes.put("minStockAlert", request.minStockAlert() == null
                ? DEFAULT_MIN_STOCK_ALERT : request.minStockAlert());
        putOrRemove(attributes, "imageUrl", blankToNull(request.imageUrl()));
        product.setAttributesJson(writeJson(attributes));
        product.setUpdatedAt(now);
    }

    private List<ProductVariantEntity> saveVariants(
            ProductEntity product,
            ProductRequest request,
            Instant now) {
        List<ProductRequest.VariantRequest> requested = requestedVariants(product, request);
        Set<String> normalizedSkus = requested.stream()
                .map(variant -> normalizeRequired(variant.sku(), "SKU biến thể không được để trống.")
                        .toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (normalizedSkus.size() != requested.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_VARIANT_SKU",
                    "Danh sách biến thể có SKU bị trùng.");
        }

        List<ProductVariantEntity> existing = variantRepository
                .findAllByProductIdAndTenantId(product.getId(), product.getTenantId());
        Map<String, ProductVariantEntity> bySku = existing.stream()
                .collect(Collectors.toMap(
                        variant -> variant.getSku().toLowerCase(Locale.ROOT),
                        variant -> variant,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<ProductVariantEntity> result = new ArrayList<>();

        for (ProductRequest.VariantRequest variantRequest : requested) {
            String sku = normalizeRequired(variantRequest.sku(), "SKU biến thể không được để trống.");
            ProductVariantEntity variant = bySku.remove(sku.toLowerCase(Locale.ROOT));
            if (variant == null) {
                variant = new ProductVariantEntity();
                variant.setId(nextId());
                variant.setTenantId(product.getTenantId());
                variant.setProductId(product.getId());
                variant.setCreatedAt(now);
                variant.setVersion(1);
                variant.setReservedStock(0);
            } else {
                variant.setVersion(variant.getVersion() + 1);
            }
            int stock = variantRequest.stockQuantity() == null ? 0 : variantRequest.stockQuantity();
            if (stock < defaultInt(variant.getReservedStock())) {
                throw new ApiException(HttpStatus.CONFLICT, "STOCK_BELOW_RESERVED",
                        "Tồn kho của SKU " + sku + " nhỏ hơn số lượng đang được giữ chỗ.");
            }
            variant.setVariantCode(sku);
            variant.setSku(sku);
            variant.setVariantName(blankToNull(variantRequest.variantName()));
            Map<String, Object> variantAttributes = readAttributes(variant.getAttributesJson());
            putOrRemove(variantAttributes, "color", blankToNull(variantRequest.color()));
            putOrRemove(variantAttributes, "size", blankToNull(variantRequest.size()));
            variant.setAttributesJson(writeJson(variantAttributes));
            variant.setPrice(nonNegative(variantRequest.price()));
            variant.setCurrency("VND");
            variant.setStockQuantity(stock);
            variant.setStatus("ACTIVE");
            variant.setDeletedAt(null);
            variant.setUpdatedAt(now);
            result.add(variantRepository.save(variant));
        }

        bySku.values().forEach(variant -> {
            if (variant.getDeletedAt() == null) {
                variant.setStatus("ARCHIVED");
                variant.setDeletedAt(now);
                variant.setUpdatedAt(now);
                variant.setVersion(variant.getVersion() + 1);
                variantRepository.save(variant);
            }
        });
        variantRepository.flush();
        return result;
    }

    private List<ProductRequest.VariantRequest> requestedVariants(
            ProductEntity product,
            ProductRequest request) {
        if (request.variants() != null && !request.variants().isEmpty()) {
            return request.variants();
        }
        return List.of(new ProductRequest.VariantRequest(
                product.getProductCode(),
                "Mặc định",
                null,
                null,
                nonNegative(request.price()),
                request.totalStock() == null ? 0 : request.totalStock()));
    }

    private int requestedStock(ProductRequest request) {
        if (request.variants() != null && !request.variants().isEmpty()) {
            return request.variants().stream()
                    .map(ProductRequest.VariantRequest::stockQuantity)
                    .mapToInt(ProductService::defaultInt)
                    .sum();
        }
        return defaultInt(request.totalStock());
    }

    private ProductResponse toResponse(ProductEntity product) {
        return toResponse(product, activeVariants(product.getId(), product.getTenantId()));
    }

    private ProductResponse toResponse(
            ProductEntity product,
            List<ProductVariantEntity> variants) {
        Map<String, Object> attributes = readAttributes(product.getAttributesJson());
        int totalStock = variants.stream().map(ProductVariantEntity::getStockQuantity)
                .mapToInt(ProductService::defaultInt).sum();
        int minAlert = intAttribute(attributes, "minStockAlert", DEFAULT_MIN_STOCK_ALERT);
        BigDecimal price = variants.stream().map(ProductVariantEntity::getPrice)
                .filter(value -> value != null).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal costPrice = decimalAttribute(attributes, "costPrice");
        var media = mediaRepository
                .findAllByProductIdAndTenantIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
                        product.getId(),
                        product.getTenantId());
        String primaryImageUrl = media.stream()
                .filter(item -> item.isPrimary() && "IMAGE".equals(item.getMediaType()))
                .map(com.backend.entity.ProductMediaEntity::getPublicUrl)
                .findFirst()
                .orElseGet(() -> media.stream()
                        .filter(item -> "IMAGE".equals(item.getMediaType()))
                        .map(com.backend.entity.ProductMediaEntity::getPublicUrl)
                        .findFirst()
                        .orElse(stringAttribute(attributes, "imageUrl")));

        List<ProductResponse.VariantResponse> variantResponses = variants.stream()
                .map(variant -> {
                    int stock = defaultInt(variant.getStockQuantity());
                    int reserved = defaultInt(variant.getReservedStock());
                    Map<String, Object> variantAttributes = readAttributes(variant.getAttributesJson());
                    return new ProductResponse.VariantResponse(
                            variant.getId(), variant.getSku(), variant.getVariantName(),
                            stringAttribute(variantAttributes, "color"),
                            stringAttribute(variantAttributes, "size"),
                            variant.getPrice(), stock, reserved, Math.max(stock - reserved, 0));
                })
                .toList();
        return new ProductResponse(
                product.getId(), product.getTenantId(), product.getName(), product.getProductCode(),
                product.getCategory(), product.getDescription(), price, costPrice, totalStock, minAlert,
                primaryImageUrl, displayStatus(product.getStatus(), totalStock, minAlert),
                product.getCreatedAt(), product.getUpdatedAt(), variantResponses,
                media.stream().map(ProductMediaService::toResponse).toList(),
                marketplaceAccountIds(product));
    }

    private void requireConnectedMarketplaceAccount(String tenantId, String accountId) {
        var account = marketplaceAccountRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(accountId, tenantId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_MARKETPLACE_TARGET",
                        "Shop được chọn không thuộc doanh nghiệp hiện tại."));
        if (!"CONNECTED".equals(account.getConnectionStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MARKETPLACE_TARGET_NOT_CONNECTED",
                    "Chỉ có thể đăng sản phẩm lên shop đang kết nối.");
        }
    }

    private void queueMarketplaceTarget(
            ProductEntity product,
            String accountId,
            Instant now) {
        jdbcClient.sql("""
                INSERT INTO marketplace_products (
                  id, tenant_id, product_id, marketplace_account_id,
                  external_product_id, external_title, raw_status,
                  canonical_status, sync_status, raw_payload,
                  created_at, updated_at, deleted_at
                ) VALUES (
                  :id, :tenantId, :productId, :accountId,
                  :productId, :title, 'PENDING_PUBLICATION',
                  'DRAFT', 'PENDING', CAST('{}' AS jsonb),
                  :now, :now, NULL
                )
                ON CONFLICT (product_id, marketplace_account_id)
                DO UPDATE SET
                  external_title = EXCLUDED.external_title,
                  sync_status = 'PENDING',
                  updated_at = EXCLUDED.updated_at,
                  deleted_at = NULL
                """)
                .param("id", nextId())
                .param("tenantId", product.getTenantId())
                .param("productId", product.getId())
                .param("accountId", accountId)
                .param("title", product.getName())
                .param("now", Timestamp.from(now))
                .update();
    }

    private List<String> marketplaceAccountIds(ProductEntity product) {
        return jdbcClient.sql("""
                SELECT marketplace_account_id
                FROM marketplace_products
                WHERE tenant_id = :tenantId
                  AND product_id = :productId
                  AND deleted_at IS NULL
                ORDER BY created_at ASC
                """)
                .param("tenantId", product.getTenantId())
                .param("productId", product.getId())
                .query(String.class)
                .list();
    }

    private List<ProductVariantEntity> activeVariants(String productId, String tenantId) {
        return variantRepository
                .findAllByProductIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(productId, tenantId);
    }

    private static String displayStatus(String storedStatus, int stock, int minAlert) {
        if (!"ACTIVE".equals(storedStatus)) {
            return storedStatus;
        }
        if (stock == 0) {
            return "OUT_OF_STOCK";
        }
        return stock <= minAlert ? "LOW_STOCK" : "ACTIVE";
    }

    private static boolean matchesDisplayStatus(String actual, String requested) {
        if ("OUT_OF_STOCK".equals(requested)) {
            return "OUT_OF_STOCK".equals(actual) || "LOW_STOCK".equals(actual);
        }
        return requested.equals(actual);
    }

    private Map<String, Object> readAttributes(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<>() { }));
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_PRODUCT_ATTRIBUTES",
                    "Thuộc tính sản phẩm trong cơ sở dữ liệu không hợp lệ.");
        }
    }

    private String writeJson(Map<String, Object> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PRODUCT_SERIALIZATION_FAILED",
                    "Không thể lưu thuộc tính sản phẩm.");
        }
    }

    private static BigDecimal decimalAttribute(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private static int intAttribute(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String stringAttribute(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static void putOrRemove(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NEGATIVE_AMOUNT",
                    "Giá sản phẩm không được là số âm.");
        }
        return value;
    }

    private static boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeRequired(String value, String message) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUIRED_VALUE_MISSING", message);
        }
        return normalized;
    }

    private static int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static String nextId() {
        return UUID.randomUUID().toString();
    }
}
