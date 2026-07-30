package com.backend.dto;

import java.time.Instant;
import java.util.List;

public record StaffUserResponse(
        String id,
        String email,
        String displayName,
        String phoneNumber,
        List<String> roles,
        String status,
        Instant createdAt
) {}
