package com.backend.service;

import com.backend.dto.ChangePasswordRequest;
import com.backend.dto.TenantLoginRequest;
import com.backend.dto.TenantSessionResponse;
import com.backend.security.TenantPrincipal;

import jakarta.servlet.http.HttpServletRequest;

public interface TenantAuthenticationService {

    LoginResult login(TenantLoginRequest request, HttpServletRequest servletRequest);

    TenantSessionResponse currentSession(TenantPrincipal principal);

    TenantSessionResponse changePassword(
            TenantPrincipal principal,
            ChangePasswordRequest request,
            HttpServletRequest servletRequest);

    void logout(String rawToken, HttpServletRequest servletRequest);

    record LoginResult(String rawToken, TenantSessionResponse session) {
    }
}
