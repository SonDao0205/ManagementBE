package com.backend.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

import com.backend.dto.ProductRequest;
import com.backend.dto.ProductResponse;
import com.backend.dto.StockAdjustmentRequest;
import com.backend.security.TenantPrincipal;
import com.backend.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Product Management")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
    @Operation(summary = "Liệt kê sản phẩm của tenant, có thể tìm kiếm và lọc theo status")
    public Page<ProductResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return productService.list(principal.tenantId(), search, status, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
    @Operation(summary = "Lấy chi tiết một sản phẩm theo ID")
    public ProductResponse getById(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        return productService.getById(principal.tenantId(), id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo sản phẩm mới cho tenant")
    public ProductResponse create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody ProductRequest req) {
        return productService.create(principal.tenantId(), req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
    @Operation(summary = "Cập nhật thông tin sản phẩm")
    public ProductResponse update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody ProductRequest req) {
        return productService.update(principal.tenantId(), id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa mềm sản phẩm (soft delete)")
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        productService.delete(principal.tenantId(), id);
    }

    @PostMapping("/{id}/adjust-stock")
    @PreAuthorize("hasAuthority('SESSION_AUTHENTICATED')")
    @Operation(summary = "Điều chỉnh tồn kho (delta dương = nhập, delta âm = xuất)")
    public ProductResponse adjustStock(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody StockAdjustmentRequest req) {
        return productService.adjustStock(principal.tenantId(), id, req);
    }
}
