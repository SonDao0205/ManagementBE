package com.backend.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.config.TenantAuthProperties;
import com.backend.dto.ChangePasswordRequest;
import com.backend.dto.TenantLoginRequest;
import com.backend.dto.TenantSessionResponse;
import com.backend.entity.LoginSessionEntity;
import com.backend.entity.SecurityAuditLogEntity;
import com.backend.entity.TenantPasswordHistoryEntity;
import com.backend.entity.TenantEntity;
import com.backend.entity.TenantUserCredentialEntity;
import com.backend.entity.TenantUserEntity;
import com.backend.repository.LoginSessionRepository;
import com.backend.repository.SecurityAuditLogRepository;
import com.backend.repository.TenantPasswordHistoryRepository;
import com.backend.repository.TenantUserRepository;
import com.backend.security.SecureTokenService;
import com.backend.security.TenantPasswordPolicy;
import com.backend.security.TenantPrincipal;
import com.backend.service.AuthenticationException;
import com.backend.service.TenantAuthenticationService;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class TenantAuthenticationServiceImpl implements TenantAuthenticationService {

    private static final Set<String> ENABLED_TENANT_STATUSES = Set.of("TRIAL", "ACTIVE");
    private final TenantUserRepository tenantUserRepository;
    private final LoginSessionRepository loginSessionRepository;
    private final SecurityAuditLogRepository auditLogRepository;
    private final TenantPasswordHistoryRepository passwordHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantPasswordPolicy passwordPolicy;
    private final SecureTokenService secureTokenService;
    private final TenantAuthProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final String dummyPasswordHash;

    public TenantAuthenticationServiceImpl(
            TenantUserRepository tenantUserRepository,
            LoginSessionRepository loginSessionRepository,
            SecurityAuditLogRepository auditLogRepository,
            TenantPasswordHistoryRepository passwordHistoryRepository,
            PasswordEncoder passwordEncoder,
            TenantPasswordPolicy passwordPolicy,
            SecureTokenService secureTokenService,
            TenantAuthProperties properties,
            TransactionTemplate transactionTemplate) {
        this.tenantUserRepository = tenantUserRepository;
        this.loginSessionRepository = loginSessionRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.secureTokenService = secureTokenService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = Clock.systemUTC();
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public LoginResult login(TenantLoginRequest request, HttpServletRequest servletRequest) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        LoginAttempt attempt = transactionTemplate.execute(status ->
                attemptLogin(email, request.password(), servletRequest));
        if (attempt == null) {
            throw new IllegalStateException("Login transaction did not return a result");
        }
        if (attempt.failure() != null) {
            throw attempt.failure();
        }
        return attempt.result();
    }

    private LoginAttempt attemptLogin(
            String email,
            String password,
            HttpServletRequest request) {
        Instant now = clock.instant();
        TenantUserEntity user = tenantUserRepository.findForLogin(email).orElse(null);

        if (user == null) {
            passwordEncoder.matches(password, dummyPasswordHash);
            audit(null, null, "TENANT_LOGIN", "FAILED", request);
            return LoginAttempt.failed(invalidCredentials());
        }

        TenantEntity tenant = user.getTenant();
        TenantUserCredentialEntity credential = user.getCredential();
        if (!ENABLED_TENANT_STATUSES.contains(tenant.getStatus())) {
            audit(tenant.getId(), user.getId(), "TENANT_LOGIN", "DENIED", request);
            return LoginAttempt.failed(new AuthenticationException(
                    HttpStatus.FORBIDDEN,
                    "TENANT_NOT_ACTIVE",
                    "Tài khoản doanh nghiệp hiện không hoạt động."));
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            audit(tenant.getId(), user.getId(), "TENANT_LOGIN", "DENIED", request);
            return LoginAttempt.failed(new AuthenticationException(
                    HttpStatus.FORBIDDEN,
                    "USER_NOT_ACTIVE",
                    "Tài khoản chưa được kích hoạt hoặc đã bị vô hiệu hóa."));
        }
        if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(now)) {
            audit(tenant.getId(), user.getId(), "TENANT_LOGIN", "DENIED", request);
            return LoginAttempt.failed(new AuthenticationException(
                    HttpStatus.LOCKED,
                    "ACCOUNT_TEMPORARILY_LOCKED",
                    "Tài khoản tạm khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau."));
        }
        if (credential.getLockedUntil() != null) {
            credential.setLockedUntil(null);
            credential.setFailedLoginCount((short) 0);
        }
        if (!"ARGON2ID".equals(credential.getPasswordAlgorithm())
                || !passwordEncoder.matches(password, credential.getPasswordHash())) {
            registerFailedAttempt(credential, now);
            audit(tenant.getId(), user.getId(), "TENANT_LOGIN", "FAILED", request);
            return LoginAttempt.failed(invalidCredentials());
        }

        credential.setFailedLoginCount((short) 0);
        credential.setLockedUntil(null);
        if (credential.getPasswordExpiresAt() != null
                && !credential.getPasswordExpiresAt().isAfter(now)) {
            credential.setMustChangePassword(true);
        }
        user.setLastLoginAt(now);
        loginSessionRepository.revokeActiveSessionsForUser(user.getId(), now);

        String rawToken = secureTokenService.newToken();
        LoginSessionEntity session = new LoginSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setActorType("TENANT_USER");
        session.setTenantUser(user);
        session.setSessionTokenHash(secureTokenService.hash(rawToken));
        session.setAuthStage(credential.isMustChangePassword()
                ? "PASSWORD_CHANGE_REQUIRED"
                : "AUTHENTICATED");
        session.setIpAddress(clientIp(request));
        session.setUserAgent(limit(request.getHeader("User-Agent"), 500));
        session.setIssuedAt(now);
        session.setExpiresAt(now.plus(properties.sessionDuration()));
        loginSessionRepository.save(session);

        List<String> roles = tenantUserRepository.findRoleCodes(user.getId(), tenant.getId());
        List<String> permissions =
                tenantUserRepository.findPermissionCodes(user.getId(), tenant.getId());
        audit(tenant.getId(), user.getId(), "TENANT_LOGIN", "SUCCEEDED", request);

        return LoginAttempt.succeeded(new LoginResult(
                rawToken,
                toResponse(user, roles, permissions, session.getExpiresAt())));
    }

    private void registerFailedAttempt(TenantUserCredentialEntity credential, Instant now) {
        short failures = (short) Math.min(
                credential.getFailedLoginCount() + 1,
                Short.MAX_VALUE);
        credential.setFailedLoginCount(failures);
        if (failures >= properties.maxLoginAttempts()) {
            credential.setLockedUntil(now.plus(properties.lockDuration()));
        }
    }

    @Override
    public TenantSessionResponse currentSession(TenantPrincipal principal) {
        return new TenantSessionResponse(
                new TenantSessionResponse.UserInfo(
                        principal.userId(),
                        principal.email(),
                        principal.displayName(),
                        principal.avatarUrl()),
                new TenantSessionResponse.TenantInfo(
                        principal.tenantId(),
                        principal.tenantCode(),
                        principal.tenantName()),
                principal.roles(),
                principal.permissions(),
                principal.mustChangePassword(),
                principal.expiresAt());
    }

    @Override
    public TenantSessionResponse changePassword(
            TenantPrincipal principal,
            ChangePasswordRequest request,
            HttpServletRequest servletRequest) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new AuthenticationException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_CONFIRMATION_MISMATCH",
                    "Xác nhận mật khẩu mới không khớp.");
        }
        passwordPolicy.validate(request.newPassword());

        TenantSessionResponse result = transactionTemplate.execute(status ->
                changePasswordInTransaction(principal, request, servletRequest));
        if (result == null) {
            throw new IllegalStateException("Password change transaction did not return a result");
        }
        return result;
    }

    private TenantSessionResponse changePasswordInTransaction(
            TenantPrincipal principal,
            ChangePasswordRequest request,
            HttpServletRequest servletRequest) {
        Instant now = clock.instant();
        TenantUserEntity user = tenantUserRepository
                .findForPasswordChange(principal.userId())
                .orElseThrow(() -> new AuthenticationException(
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED",
                        "Phiên đăng nhập không hợp lệ hoặc đã hết hạn."));
        TenantUserCredentialEntity credential = user.getCredential();

        if (!"ARGON2ID".equals(credential.getPasswordAlgorithm())
                || !passwordEncoder.matches(
                        request.currentPassword(),
                        credential.getPasswordHash())) {
            throw new AuthenticationException(
                    HttpStatus.BAD_REQUEST,
                    "CURRENT_PASSWORD_INVALID",
                    "Mật khẩu hiện tại không chính xác.");
        }
        if (passwordEncoder.matches(request.newPassword(), credential.getPasswordHash())
                || wasRecentlyUsed(user.getId(), request.newPassword())) {
            throw new AuthenticationException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_REUSED",
                    "Mật khẩu mới không được trùng mật khẩu hiện tại hoặc 5 mật khẩu gần nhất.");
        }

        TenantPasswordHistoryEntity history = new TenantPasswordHistoryEntity();
        history.setTenantUserId(user.getId());
        history.setPasswordHash(credential.getPasswordHash());
        history.setPasswordAlgorithm(credential.getPasswordAlgorithm());
        history.setChangedAt(now);
        history.setChangedByType("TENANT_USER");
        passwordHistoryRepository.save(history);

        credential.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        credential.setPasswordAlgorithm("ARGON2ID");
        credential.setMustChangePassword(false);
        credential.setPasswordChangedAt(now);
        credential.setPasswordExpiresAt(null);
        credential.setFailedLoginCount((short) 0);
        credential.setLockedUntil(null);
        credential.setResetTokenHash(null);
        credential.setResetTokenExpiresAt(null);
        credential.setCredentialVersion(credential.getCredentialVersion() + 1);

        int updatedSession = loginSessionRepository.markAuthenticated(
                principal.sessionId(),
                user.getId(),
                now);
        if (updatedSession != 1) {
            throw new AuthenticationException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
        }
        loginSessionRepository.revokeOtherSessions(
                user.getId(),
                principal.sessionId(),
                now);

        List<String> roles = tenantUserRepository.findRoleCodes(
                user.getId(),
                user.getTenant().getId());
        List<String> permissions = tenantUserRepository.findPermissionCodes(
                user.getId(),
                user.getTenant().getId());
        audit(
                user.getTenant().getId(),
                user.getId(),
                "TENANT_PASSWORD_CHANGED",
                "SUCCEEDED",
                servletRequest);
        return toResponse(user, roles, permissions, principal.expiresAt());
    }

    private boolean wasRecentlyUsed(String userId, String newPassword) {
        return passwordHistoryRepository
                .findTop5ByTenantUserIdOrderByChangedAtDesc(userId)
                .stream()
                .anyMatch(history -> passwordEncoder.matches(
                        newPassword,
                        history.getPasswordHash()));
    }

    @Override
    public void logout(String rawToken, HttpServletRequest request) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            String tokenHash = secureTokenService.hash(rawToken);
            loginSessionRepository.findActiveTenantSession(tokenHash, clock.instant())
                    .ifPresent(session -> {
                        TenantUserEntity user = session.getTenantUser();
                        loginSessionRepository.revokeByTokenHash(tokenHash, clock.instant());
                        audit(user.getTenant().getId(), user.getId(), "TENANT_LOGOUT", "SUCCEEDED", request);
                    });
        });
    }

    private TenantSessionResponse toResponse(
            TenantUserEntity user,
            List<String> roles,
            List<String> permissions,
            Instant expiresAt) {
        TenantEntity tenant = user.getTenant();
        return new TenantSessionResponse(
                new TenantSessionResponse.UserInfo(
                        user.getId(),
                        user.getEmail(),
                        user.getDisplayName(),
                        user.getAvatarUrl()),
                new TenantSessionResponse.TenantInfo(
                        tenant.getId(),
                        tenant.getTenantCode(),
                        tenant.getTenantName()),
                List.copyOf(roles),
                List.copyOf(permissions),
                user.getCredential().isMustChangePassword(),
                expiresAt);
    }

    private void audit(
            String tenantId,
            String actorId,
            String actionCode,
            String result,
            HttpServletRequest request) {
        SecurityAuditLogEntity log = new SecurityAuditLogEntity();
        log.setTenantId(tenantId);
        log.setActorType("TENANT_USER");
        log.setActorId(actorId);
        log.setActionCode(actionCode);
        log.setTargetType("TENANT_USER");
        log.setTargetId(actorId);
        log.setResult(result);
        log.setIpAddress(clientIp(request));
        log.setMetadataJson("{}");
        log.setOccurredAt(clock.instant());
        auditLogRepository.save(log);
    }

    private static String clientIp(HttpServletRequest request) {
        return limit(request.getRemoteAddr(), 45);
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static AuthenticationException invalidCredentials() {
        return new AuthenticationException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Email hoặc mật khẩu không chính xác.");
    }

    private record LoginAttempt(LoginResult result, AuthenticationException failure) {

        private static LoginAttempt succeeded(LoginResult result) {
            return new LoginAttempt(result, null);
        }

        private static LoginAttempt failed(AuthenticationException failure) {
            return new LoginAttempt(null, failure);
        }
    }
}
