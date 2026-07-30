package com.backend.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.backend.dto.CreateStaffRequest;
import com.backend.dto.UpdateStaffRequest;
import com.backend.dto.StaffUserResponse;
import com.backend.dto.ToggleStatusRequest;
import com.backend.dto.ResetPasswordRequest;
import com.backend.security.TenantPrincipal;
import com.backend.service.CskhAccountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/staff")
@Tag(name = "Staff Management", description = "Endpoints for Shop Admins to manage internal CSKH staff accounts")
@PreAuthorize("hasRole('TENANT_MANAGER')")
public class StaffController {

    private final CskhAccountService cskhAccountService;

    public StaffController(CskhAccountService cskhAccountService) {
        this.cskhAccountService = cskhAccountService;
    }

    @GetMapping
    @Operation(summary = "Lấy danh sách nhân viên CSKH của cửa hàng")
    public List<StaffUserResponse> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return cskhAccountService.getStaffList(principal.tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo mới tài khoản nhân viên CSKH")
    public StaffUserResponse create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody CreateStaffRequest req) {
        return cskhAccountService.createStaff(principal.tenantId(), req, principal.userId());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật thông tin tài khoản nhân viên")
    public StaffUserResponse update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody UpdateStaffRequest req) {
        return cskhAccountService.updateStaff(principal.tenantId(), id, req);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Khóa hoặc mở khóa tài khoản nhân viên")
    public StaffUserResponse toggleStatus(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody ToggleStatusRequest req) {
        return cskhAccountService.toggleStaffStatus(principal.tenantId(), id, req.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa tài khoản nhân viên (xóa mềm)")
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id) {
        cskhAccountService.deleteStaff(principal.tenantId(), id);
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Đặt lại mật khẩu cho tài khoản nhân viên")
    public void resetPassword(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody ResetPasswordRequest req) {
        cskhAccountService.resetPassword(principal.tenantId(), id, req.password());
    }
}
