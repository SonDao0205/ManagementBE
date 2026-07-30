package com.backend.controller;

import java.util.List;

import org.springframework.data.domain.Page;
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

import com.backend.dto.CreateCustomerRequest;
import com.backend.dto.CustomerDetailResponse;
import com.backend.dto.CustomerInteractionResponse;
import com.backend.dto.CustomerResponse;
import com.backend.dto.MergeCustomersRequest;
import com.backend.dto.UpdateCustomerRequest;
import com.backend.security.TenantPrincipal;
import com.backend.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customer CRM (CDP)", description = "Endpoints for managing unified customer profiles and deduplication")
@PreAuthorize("hasAnyRole('TENANT_MANAGER', 'CS_AGENT')")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    @Operation(summary = "Lấy danh sách khách hàng (CRM)")
    public Page<CustomerResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return customerService.getCustomerList(principal.tenantId(), search, status, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lấy chi tiết hồ sơ khách hàng (CDP)")
    public CustomerDetailResponse detail(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        return customerService.getCustomerDetail(principal.tenantId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo mới một hồ sơ khách hàng")
    public CustomerResponse create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody CreateCustomerRequest request) {
        return customerService.createCustomer(principal.tenantId(), request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật thông tin khách hàng")
    public CustomerResponse update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return customerService.updateCustomer(principal.tenantId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa mềm hồ sơ khách hàng")
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        customerService.deleteCustomer(principal.tenantId(), id);
    }

    @GetMapping("/{id}/duplicates")
    @Operation(summary = "Lấy danh sách khách hàng trùng lặp tiềm năng (cùng tên)")
    public List<CustomerResponse> duplicates(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        return customerService.getPotentialDuplicates(principal.tenantId(), id);
    }

    @GetMapping("/{id}/interactions")
    @Operation(summary = "Lấy lịch sử tương tác khách hàng")
    public List<CustomerInteractionResponse> interactions(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        return customerService.getInteractions(principal.tenantId(), id);
    }

    @PostMapping("/merge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Gộp hai tài khoản khách hàng trùng lặp theo cách thủ công")
    public void merge(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody MergeCustomersRequest request) {
        customerService.mergeCustomers(principal.tenantId(), request, principal.userId());
    }
}
