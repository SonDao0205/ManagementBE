package com.backend.dto;

import java.time.Instant;
import java.util.List;

public record TenantSessionResponse(
        UserInfo user,
        TenantInfo tenant,
        List<String> roles,
        List<String> permissions,
        boolean mustChangePassword,
        Instant expiresAt) {

    public record UserInfo(
            String id,
            String email,
            String displayName,
            String avatarUrl) {
    }

    public record TenantInfo(
            String id,
            String code,
            String name) {
    }
}
