package com.backend.service;

public interface StaffCredentialEmailService {

    void sendTemporaryPassword(
            String email,
            String displayName,
            String tenantName,
            String temporaryPassword);
}
