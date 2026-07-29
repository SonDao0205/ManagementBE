package com.backend.security;

import java.time.Instant;
import java.util.List;

public record TenantPrincipal(
        String sessionId,
        String userId,
        String email,
        String displayName,
        String avatarUrl,
        String tenantId,
        String tenantCode,
        String tenantName,
        List<String> roles,
        List<String> permissions,
        boolean mustChangePassword,
        Instant expiresAt) {
}
