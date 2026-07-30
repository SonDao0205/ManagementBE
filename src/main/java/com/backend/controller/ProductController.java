package com.backend.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.backend.dto.ProductResponse;
import com.backend.dto.ProductStockAdjustmentRequest;
import com.backend.dto.ProductSyncResponse;
import com.backend.dto.ProductUpsertRequest;
import com.backend.security.TenantPrincipal;
import com.backend.service.ProductService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
@PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                safeSize,
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return productService.list(
                principal.tenantId(),
                search,
                status,
                pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody ProductUpsertRequest request) {
        return productService.create(principal, request);
    }

    @PutMapping("/{productId}")
    public ProductResponse update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String productId,
            @Valid @RequestBody ProductUpsertRequest request) {
        return productService.update(principal, productId, request);
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String productId) {
        productService.delete(principal.tenantId(), productId);
    }

    @PostMapping("/{productId}/adjust-stock")
    public ProductResponse adjustStock(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String productId,
            @Valid @RequestBody ProductStockAdjustmentRequest request) {
        return productService.adjustStock(
                principal.tenantId(),
                productId,
                request);
    }

    @PostMapping("/sync")
    public ProductSyncResponse sync(
            @AuthenticationPrincipal TenantPrincipal principal) {
        return productService.syncAll(principal);
    }
}
