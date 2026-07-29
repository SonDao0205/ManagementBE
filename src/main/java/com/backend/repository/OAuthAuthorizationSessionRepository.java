package com.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.backend.entity.OAuthAuthorizationSessionEntity;

import jakarta.persistence.LockModeType;

public interface OAuthAuthorizationSessionRepository
        extends JpaRepository<OAuthAuthorizationSessionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthAuthorizationSessionEntity> findByStateHash(String stateHash);
}
