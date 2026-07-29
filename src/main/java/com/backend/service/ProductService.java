package com.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.backend.dto.ProductRequest;
import com.backend.dto.ProductResponse;
import com.backend.dto.StockAdjustmentRequest;
import com.backend.entity.ProductEntity;
import com.backend.entity.ProductVariantEntity;
import com.backend.repository.ProductRepository;
import com.backend.repository.ProductVariantRepository;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    public ProductService(ProductRepository productRepository,
                          ProductVariantRepository variantRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
    }

    // ─────────────────────────────────────────────────────────
    // List / Search
    // ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String tenantId, String search, String status, Pageable pageable) {
        Page<ProductEntity> page;

        boolean hasSearch = search != null && !search.isBlank();
        boolean hasStatus = status != null && !status.isBlank();

        if (hasSearch) {
            page = productRepository.findByTenantIdAndSearch(tenantId, search, pageable);
        } else if (hasStatus) {
            page = productRepository.findAllByTenantIdAndStatusAndDeletedAtIsNull(tenantId, status, pageable);
        } else {
            page = productRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable);
        }

        return page.map(p -> toResponse(p));
    }

    // ─────────────────────────────────────────────────────────
    // Get by ID
    // ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ProductResponse getById(String tenantId, String id) {
        ProductEntity product = findOrThrow(tenantId, id);
        return toResponse(product);
    }

    // ─────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────

    public ProductResponse create(String tenantId, ProductRequest req) {
        Instant now = Instant.now();

        ProductEntity product = new ProductEntity();
        product.setId(nextId());
        product.setTenantId(tenantId);
        applyRequest(product, req, now);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        productRepository.save(product);

        List<ProductVariantEntity> variants = saveVariants(product.getId(), tenantId, req, now);
        return ProductResponse.from(product, variants);
    }

    // ─────────────────────────────────────────────────────────
    // Update
    // ─────────────────────────────────────────────────────────

    public ProductResponse update(String tenantId, String id, ProductRequest req) {
        Instant now = Instant.now();

        ProductEntity product = findOrThrow(tenantId, id);
        applyRequest(product, req, now);
        product.setUpdatedAt(now);
        productRepository.save(product);

        // Replace variants: delete old, insert new
        List<ProductVariantEntity> oldVariants = variantRepository.findAllByProductIdAndTenantId(id, tenantId);
        variantRepository.deleteAll(oldVariants);

        List<ProductVariantEntity> variants = saveVariants(id, tenantId, req, now);
        return ProductResponse.from(product, variants);
    }

    // ─────────────────────────────────────────────────────────
    // Soft Delete
    // ─────────────────────────────────────────────────────────

    public void delete(String tenantId, String id) {
        ProductEntity product = findOrThrow(tenantId, id);
        product.setDeletedAt(Instant.now());
        productRepository.save(product);
    }

    // ─────────────────────────────────────────────────────────
    // Stock Adjustment
    // ─────────────────────────────────────────────────────────

    public ProductResponse adjustStock(String tenantId, String id, StockAdjustmentRequest req) {
        ProductEntity product = findOrThrow(tenantId, id);

        int currentStock = product.getTotalStock() == null ? 0 : product.getTotalStock();
        int newStock = currentStock + req.delta();

        if (newStock < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stock cannot go below zero. Current stock: " + currentStock
                            + ", requested delta: " + req.delta());
        }

        product.setTotalStock(newStock);
        product.setStatus(resolveStatus(newStock, product.getMinStockAlert()));
        product.setUpdatedAt(Instant.now());
        productRepository.save(product);

        List<ProductVariantEntity> variants = variantRepository.findAllByProductIdAndTenantId(id, tenantId);
        return ProductResponse.from(product, variants);
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private ProductEntity findOrThrow(String tenantId, String id) {
        return productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Product not found: " + id));
    }

    private void applyRequest(ProductEntity product, ProductRequest req, Instant now) {
        product.setName(req.name());
        product.setProductCode(req.productCode());
        product.setCategory(req.category());
        product.setDescription(req.description());
        product.setPrice(req.price() != null ? req.price() : BigDecimal.ZERO);
        product.setCostPrice(req.costPrice() != null ? req.costPrice() : BigDecimal.ZERO);
        product.setTotalStock(req.totalStock() != null ? req.totalStock() : 0);
        product.setMinStockAlert(req.minStockAlert() != null ? req.minStockAlert() : 5);
        product.setImageUrl(req.imageUrl());

        if (req.status() != null && !req.status().isBlank()) {
            product.setStatus(req.status());
        } else {
            product.setStatus(resolveStatus(
                    product.getTotalStock() == null ? 0 : product.getTotalStock(),
                    product.getMinStockAlert() == null ? 5 : product.getMinStockAlert()));
        }
    }

    private List<ProductVariantEntity> saveVariants(String productId, String tenantId,
                                                     ProductRequest req, Instant now) {
        if (req.variants() == null || req.variants().isEmpty()) {
            return List.of();
        }

        List<ProductVariantEntity> entities = req.variants().stream()
                .map(v -> {
                    ProductVariantEntity e = new ProductVariantEntity();
                    e.setId(nextId());
                    e.setProductId(productId);
                    e.setTenantId(tenantId);
                    e.setSku(v.sku());
                    e.setVariantName(v.variantName());
                    e.setPrice(v.price());
                    e.setStockQuantity(v.stockQuantity() != null ? v.stockQuantity() : 0);
                    e.setCreatedAt(now);
                    e.setUpdatedAt(now);
                    return e;
                })
                .toList();

        return variantRepository.saveAll(entities);
    }

    private static String resolveStatus(int stock, Integer minAlert) {
        int alert = minAlert == null ? 5 : minAlert;
        if (stock == 0) return "OUT_OF_STOCK";
        if (stock <= alert) return "LOW_STOCK";
        return "ACTIVE";
    }

    private static String nextId() {
        return UUID.randomUUID().toString();
    }

    private ProductResponse toResponse(ProductEntity p) {
        List<ProductVariantEntity> variants = variantRepository.findAllByProductIdAndTenantId(
                p.getId(), p.getTenantId());
        return ProductResponse.from(p, variants);
    }
}
