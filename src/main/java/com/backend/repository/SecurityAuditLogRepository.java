package com.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.entity.SecurityAuditLogEntity;

public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLogEntity, Long> {
}
