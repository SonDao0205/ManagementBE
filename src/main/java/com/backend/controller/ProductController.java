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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.backend.dto.ProductRequest;
import com.backend.dto.ProductResponse;
import com.backend.dto.ProductMediaOrderRequest;
import com.backend.dto.ProductMediaResponse;
import com.backend.dto.ProductMarketplaceSyncRequest;
import com.backend.dto.ProductMarketplaceSyncResponse;
import com.backend.dto.StockAdjustmentRequest;
import com.backend.security.TenantPrincipal;
import com.backend.service.ProductService;
import com.backend.service.ProductMediaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Product Management")
@Validated
public class ProductController {

    private final ProductService productService;
    private final ProductMediaService productMediaService;

    public ProductController(
            ProductService productService,
            ProductMediaService productMediaService) {
        this.productService = productService;
        this.productMediaService = productMediaService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PRODUCT.READ')")
    @Operation(summary = "Liệt kê sản phẩm của tenant, có thể tìm kiếm và lọc theo status")
    public Page<ProductResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productService.list(principal.tenantId(), search, status, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.READ')")
    @Operation(summary = "Lấy chi tiết một sản phẩm theo ID")
    public ProductResponse getById(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        return productService.getById(principal.tenantId(), id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PRODUCT.CREATE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo sản phẩm mới cho tenant")
    public ProductResponse create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody ProductRequest req) {
        return productService.create(principal, req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    @Operation(summary = "Cập nhật thông tin sản phẩm")
    public ProductResponse update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody ProductRequest req) {
        return productService.update(principal.tenantId(), id, req);
    }

    @PostMapping("/marketplace-sync")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    @Operation(summary = "Xếp hàng đăng các sản phẩm được chọn lên một hoặc nhiều shop")
    public ProductMarketplaceSyncResponse queueMarketplaceSync(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody ProductMarketplaceSyncRequest request) {
        return productService.queueMarketplaceSync(principal.tenantId(), request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa mềm sản phẩm (soft delete)")
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        productService.delete(principal.tenantId(), id);
    }

    @PostMapping("/{id}/adjust-stock")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    @Operation(summary = "Điều chỉnh tồn kho (delta dương = nhập, delta âm = xuất)")
    public ProductResponse adjustStock(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody StockAdjustmentRequest req) {
        return productService.adjustStock(principal.tenantId(), id, req);
    }

    @PostMapping(value = "/{id}/media", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tải nhiều ảnh hoặc video của sản phẩm lên Cloudinary")
    public java.util.List<ProductMediaResponse> uploadMedia(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @RequestPart("files") java.util.List<org.springframework.web.multipart.MultipartFile> files) {
        return productMediaService.upload(principal.tenantId(), id, files);
    }

    @PutMapping("/{id}/media/order")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    @Operation(summary = "Sắp xếp media và chọn media chính")
    public java.util.List<ProductMediaResponse> reorderMedia(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody ProductMediaOrderRequest request) {
        return productMediaService.reorder(principal.tenantId(), id, request);
    }

    @DeleteMapping("/{id}/media/{mediaId}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa media khỏi sản phẩm và Cloudinary")
    public void deleteMedia(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @PathVariable String mediaId) {
        productMediaService.delete(principal.tenantId(), id, mediaId);
    }
}
