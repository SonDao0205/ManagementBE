package com.backend.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.config.TenantAuthProperties;
import com.backend.dto.ChangePasswordRequest;
import com.backend.dto.CsrfTokenResponse;
import com.backend.dto.TenantLoginRequest;
import com.backend.dto.TenantSessionResponse;
import com.backend.security.TenantPrincipal;
import com.backend.security.TenantSessionAuthenticationFilter;
import com.backend.service.TenantAuthenticationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Tenant authentication")
public class TenantAuthController {

    private final TenantAuthenticationService authenticationService;
    private final TenantAuthProperties properties;

    public TenantAuthController(
            TenantAuthenticationService authenticationService,
            TenantAuthProperties properties) {
        this.authenticationService = authenticationService;
        this.properties = properties;
    }

    @GetMapping("/csrf")
    @Operation(summary = "Cấp CSRF token trước khi gọi API làm thay đổi dữ liệu")
    public CsrfTokenResponse csrf(CsrfToken csrfToken) {
        return new CsrfTokenResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken());
    }

    @PostMapping("/login")
    @Operation(summary = "Đăng nhập tài khoản thuộc một tenant")
    public ResponseEntity<TenantSessionResponse> login(
            @Valid @RequestBody TenantLoginRequest request,
            HttpServletRequest servletRequest) {
        TenantAuthenticationService.LoginResult result =
                authenticationService.login(request, servletRequest);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.rawToken()).toString())
                .body(result.session());
    }

    @GetMapping("/me")
    @Operation(summary = "Lấy tài khoản, tenant, role và permission của phiên hiện tại")
    public TenantSessionResponse me(@AuthenticationPrincipal TenantPrincipal principal) {
        return authenticationService.currentSession(principal);
    }

    @PostMapping("/change-password")
    @Operation(summary = "Đổi mật khẩu bắt buộc ở lần đăng nhập đầu tiên")
    public TenantSessionResponse changePassword(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest servletRequest) {
        return authenticationService.changePassword(principal, request, servletRequest);
    }

    @PostMapping("/logout")
    @Operation(summary = "Thu hồi phiên hiện tại và xóa cookie đăng nhập")
    public ResponseEntity<Void> logout(HttpServletRequest servletRequest) {
        String rawToken = TenantSessionAuthenticationFilter.readCookie(
                servletRequest,
                properties.cookieName());
        authenticationService.logout(rawToken, servletRequest);
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString())
                .build();
    }

    private ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(properties.cookieName(), token)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path("/")
                .maxAge(properties.sessionDuration())
                .build();
    }

    private ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(properties.cookieName(), "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path("/")
                .maxAge(0)
                .build();
    }
}
