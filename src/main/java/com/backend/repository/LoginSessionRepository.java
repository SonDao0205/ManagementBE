package com.backend.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.entity.LoginSessionEntity;

public interface LoginSessionRepository extends JpaRepository<LoginSessionEntity, String> {

    @Query("""
            select s from LoginSessionEntity s
            join fetch s.tenantUser u
            join fetch u.tenant t
            join fetch u.credential c
            where s.sessionTokenHash = :tokenHash
              and s.actorType = 'TENANT_USER'
              and s.authStage in ('PASSWORD_CHANGE_REQUIRED', 'AUTHENTICATED')
              and s.revokedAt is null
              and s.expiresAt > :now
              and u.status = 'ACTIVE'
              and u.deletedAt is null
              and t.status in ('TRIAL', 'ACTIVE')
              and t.deletedAt is null
            """)
    Optional<LoginSessionEntity> findActiveTenantSession(
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LoginSessionEntity s
               set s.revokedAt = :now
             where s.tenantUser.id = :userId
               and s.actorType = 'TENANT_USER'
               and s.revokedAt is null
            """)
    int revokeActiveSessionsForUser(
            @Param("userId") String userId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LoginSessionEntity s
               set s.revokedAt = :now
             where s.tenantUser.id = :userId
               and s.id <> :currentSessionId
               and s.actorType = 'TENANT_USER'
               and s.revokedAt is null
            """)
    int revokeOtherSessions(
            @Param("userId") String userId,
            @Param("currentSessionId") String currentSessionId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LoginSessionEntity s
               set s.authStage = 'AUTHENTICATED'
             where s.id = :sessionId
               and s.tenantUser.id = :userId
               and s.actorType = 'TENANT_USER'
               and s.revokedAt is null
               and s.expiresAt > :now
            """)
    int markAuthenticated(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LoginSessionEntity s
               set s.revokedAt = :now
             where s.sessionTokenHash = :tokenHash
               and s.actorType = 'TENANT_USER'
               and s.revokedAt is null
            """)
    int revokeByTokenHash(
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now);
}
