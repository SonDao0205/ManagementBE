package com.backend.service.impl;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import com.backend.config.MarketplaceProperties;
import com.backend.dto.MarketplaceAuthorizationResponse;
import com.backend.dto.MarketplaceAuthorizeRequest;
import com.backend.dto.MarketplaceConnectionResponse;
import com.backend.entity.MarketplaceAccountEntity;
import com.backend.entity.MarketplaceConnectionHistoryEntity;
import com.backend.entity.MarketplaceCredentialEntity;
import com.backend.entity.MarketplaceEntity;
import com.backend.entity.OAuthAuthorizationSessionEntity;
import com.backend.entity.SecurityAuditLogEntity;
import com.backend.marketplace.MarketplaceConnector;
import com.backend.marketplace.MarketplaceConnectorRegistry;
import com.backend.repository.MarketplaceAccountRepository;
import com.backend.repository.MarketplaceConnectionHistoryRepository;
import com.backend.repository.MarketplaceCredentialRepository;
import com.backend.repository.MarketplaceRepository;
import com.backend.repository.OAuthAuthorizationSessionRepository;
import com.backend.repository.SecurityAuditLogRepository;
import com.backend.security.CredentialEncryptionService;
import com.backend.security.SecureTokenService;
import com.backend.security.TenantPrincipal;
import com.backend.service.AuthenticationException;
import com.backend.service.MarketplaceConnectionService;

@Service
public class MarketplaceConnectionServiceImpl implements MarketplaceConnectionService {

    private static final String CONNECTED = "CONNECTED";
    private static final String PRODUCTION_FRONTEND_HOST = "app.managementomni.me";
    private static final List<String> REQUESTED_SCOPES = List.of(
            "product.read",
            "product.write",
            "order.read",
            "chat.read");
    private static final Pattern JSON_STRING =
            Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private final MarketplaceRepository marketplaceRepository;
    private final MarketplaceAccountRepository accountRepository;
    private final MarketplaceCredentialRepository credentialRepository;
    private final OAuthAuthorizationSessionRepository authorizationSessionRepository;
    private final MarketplaceConnectionHistoryRepository connectionHistoryRepository;
    private final SecurityAuditLogRepository auditLogRepository;
    private final MarketplaceConnectorRegistry connectorRegistry;
    private final MarketplaceProperties properties;
    private final SecureTokenService secureTokenService;
    private final CredentialEncryptionService encryptionService;

    public MarketplaceConnectionServiceImpl(
            MarketplaceRepository marketplaceRepository,
            MarketplaceAccountRepository accountRepository,
            MarketplaceCredentialRepository credentialRepository,
            OAuthAuthorizationSessionRepository authorizationSessionRepository,
            MarketplaceConnectionHistoryRepository connectionHistoryRepository,
            SecurityAuditLogRepository auditLogRepository,
            MarketplaceConnectorRegistry connectorRegistry,
            MarketplaceProperties properties,
            SecureTokenService secureTokenService,
            CredentialEncryptionService encryptionService) {
        this.marketplaceRepository = marketplaceRepository;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.authorizationSessionRepository = authorizationSessionRepository;
        this.connectionHistoryRepository = connectionHistoryRepository;
        this.auditLogRepository = auditLogRepository;
        this.connectorRegistry = connectorRegistry;
        this.properties = properties;
        this.secureTokenService = secureTokenService;
        this.encryptionService = encryptionService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarketplaceConnectionResponse> list(TenantPrincipal principal) {
        List<MarketplaceAccountEntity> accounts =
                accountRepository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        principal.tenantId());
        Map<String, MarketplaceEntity> marketplaces = new HashMap<>();
        marketplaceRepository.findAllById(
                accounts.stream().map(MarketplaceAccountEntity::getMarketplaceId).toList())
                .forEach(marketplace -> marketplaces.put(marketplace.getId(), marketplace));

        return accounts.stream()
                .map(account -> toResponse(
                        account,
                        requireMarketplace(marketplaces.get(account.getMarketplaceId())),
                        credentialRepository.findByMarketplaceAccountId(account.getId())
                                .orElse(null)))
                .sorted(Comparator.comparing(
                        MarketplaceConnectionResponse::marketplaceName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    @Transactional
    public MarketplaceAuthorizationResponse beginAuthorization(
            TenantPrincipal principal,
            MarketplaceAuthorizeRequest request) {
        String marketplaceCode = normalizeMarketplaceCode(request.marketplace());
        MarketplaceEntity marketplace = requireMarketplace(marketplaceCode);
        URI returnUri = validateReturnUrl(request.returnUrl());
        Instant now = Instant.now();
        String rawState = secureTokenService.newToken();

        OAuthAuthorizationSessionEntity authorization =
                new OAuthAuthorizationSessionEntity();
        authorization.setId(UUID.randomUUID().toString());
        authorization.setTenantId(principal.tenantId());
        authorization.setTenantUserId(principal.userId());
        authorization.setMarketplaceId(marketplace.getId());
        authorization.setStateHash(secureTokenService.hash(rawState));
        authorization.setRequestedScopesJson(toJsonArray(REQUESTED_SCOPES));
        authorization.setReturnUrl(returnUri.toString());
        authorization.setStatus("PENDING");
        authorization.setExpiresAt(now.plus(properties.authorizationTtl()));
        authorization.setCreatedAt(now);
        authorization.setUpdatedAt(now);
        authorizationSessionRepository.save(authorization);

        MarketplaceConnector connector = connectorRegistry.require(marketplaceCode);
        String authorizationUrl = connector.authorizationUrl(
                rawState,
                callbackUri(marketplaceCode));
        audit(
                principal,
                "MARKETPLACE.AUTHORIZATION_STARTED",
                "OAUTH_AUTHORIZATION_SESSION",
                authorization.getId(),
                "SUCCEEDED",
                "{\"marketplace\":\"" + marketplaceCode + "\"}");
        return new MarketplaceAuthorizationResponse(
                marketplaceCode,
                authorizationUrl,
                authorization.getExpiresAt());
    }

    @Override
    @Transactional
    public URI completeAuthorization(
            TenantPrincipal principal,
            String marketplaceCode,
            String authorizationCode,
            String state) {
        String normalizedCode = normalizeMarketplaceCode(marketplaceCode);
        if (authorizationCode == null || authorizationCode.isBlank()
                || state == null || state.isBlank()) {
            throw badRequest(
                    "INVALID_OAUTH_CALLBACK",
                    "Sàn trả về dữ liệu xác thực không đầy đủ.");
        }

        MarketplaceEntity marketplace = requireMarketplace(normalizedCode);
        OAuthAuthorizationSessionEntity authorization = authorizationSessionRepository
                .findByStateHash(secureTokenService.hash(state))
                .orElseThrow(() -> badRequest(
                        "INVALID_OAUTH_STATE",
                        "Phiên liên kết sàn không hợp lệ."));
        Instant now = Instant.now();
        validateAuthorizationBinding(authorization, marketplace, principal);
        if (!authorization.getExpiresAt().isAfter(now)) {
            authorization.setStatus("EXPIRED");
            authorization.setConsumedAt(now);
            authorization.setFailureCode("OAUTH_STATE_EXPIRED");
            authorization.setUpdatedAt(now);
            authorizationSessionRepository.save(authorization);
            audit(
                    principal,
                    "MARKETPLACE.CONNECT_FAILED",
                    "OAUTH_AUTHORIZATION_SESSION",
                    authorization.getId(),
                    "FAILED",
                    "{\"marketplace\":\"" + normalizedCode
                            + "\",\"code\":\"OAUTH_STATE_EXPIRED\"}");
            return callbackResultUri(
                    authorization.getReturnUrl(),
                    "error",
                    normalizedCode,
                    "OAUTH_STATE_EXPIRED");
        }

        // Consume before exchanging the code so the same callback cannot be replayed.
        authorization.setStatus("AUTHORIZED");
        authorization.setConsumedAt(now);
        authorization.setUpdatedAt(now);
        authorizationSessionRepository.save(authorization);

        MarketplaceConnector connector = null;
        MarketplaceConnector.TokenResult token = null;
        try {
            connector = connectorRegistry.require(normalizedCode);
            token = connector.exchangeAuthorizationCode(
                            authorizationCode,
                            callbackUri(normalizedCode));
            MarketplaceConnector.ShopProfile shop =
                    connector.getShopProfile(token.accessToken());
            MarketplaceAccountEntity account =
                    saveConnectedAccount(principal, marketplace, token, shop, now);
            audit(
                    principal,
                    "MARKETPLACE.CONNECTED",
                    "MARKETPLACE_ACCOUNT",
                    account.getId(),
                    "SUCCEEDED",
                    "{\"marketplace\":\"" + normalizedCode + "\"}");
            return callbackResultUri(
                    authorization.getReturnUrl(),
                    "success",
                    normalizedCode,
                    null);
        } catch (AuthenticationException exception) {
            revokeIssuedToken(connector, token);
            authorization.setStatus("FAILED");
            authorization.setFailureCode(exception.getCode());
            authorization.setUpdatedAt(Instant.now());
            authorizationSessionRepository.save(authorization);
            audit(
                    principal,
                    "MARKETPLACE.CONNECT_FAILED",
                    "OAUTH_AUTHORIZATION_SESSION",
                    authorization.getId(),
                    "FAILED",
                    "{\"marketplace\":\"" + normalizedCode
                            + "\",\"code\":\"" + exception.getCode() + "\"}");
            return callbackResultUri(
                    authorization.getReturnUrl(),
                    "error",
                    normalizedCode,
                    exception.getCode());
        }
    }

    private static void revokeIssuedToken(
            MarketplaceConnector connector,
            MarketplaceConnector.TokenResult token) {
        if (connector == null || token == null) {
            return;
        }
        try {
            connector.revoke(token.accessToken());
            if (token.refreshToken() != null && !token.refreshToken().isBlank()) {
                connector.revoke(token.refreshToken());
            }
        } catch (AuthenticationException ignored) {
            // The local ownership rejection must not depend on simulator uptime.
        }
    }

    @Override
    @Transactional
    public URI denyAuthorization(
            TenantPrincipal principal,
            String marketplaceCode,
            String state) {
        String normalizedCode = normalizeMarketplaceCode(marketplaceCode);
        if (state == null || state.isBlank()) {
            throw badRequest(
                    "INVALID_OAUTH_CALLBACK",
                    "Sàn trả về dữ liệu xác thực không đầy đủ.");
        }
        MarketplaceEntity marketplace = requireMarketplace(normalizedCode);
        OAuthAuthorizationSessionEntity authorization = authorizationSessionRepository
                .findByStateHash(secureTokenService.hash(state))
                .orElseThrow(() -> badRequest(
                        "INVALID_OAUTH_STATE",
                        "Phiên liên kết sàn không hợp lệ."));
        Instant now = Instant.now();
        validateAuthorizationBinding(authorization, marketplace, principal);
        String failureCode;
        if (!authorization.getExpiresAt().isAfter(now)) {
            authorization.setStatus("EXPIRED");
            failureCode = "OAUTH_STATE_EXPIRED";
        } else {
            authorization.setStatus("DENIED");
            failureCode = "OAUTH_ACCESS_DENIED";
        }
        authorization.setConsumedAt(now);
        authorization.setFailureCode(failureCode);
        authorization.setUpdatedAt(now);
        authorizationSessionRepository.save(authorization);
        audit(
                principal,
                "MARKETPLACE.CONNECT_DENIED",
                "OAUTH_AUTHORIZATION_SESSION",
                authorization.getId(),
                "DENIED",
                "{\"marketplace\":\"" + normalizedCode
                        + "\",\"code\":\"" + failureCode + "\"}");
        return callbackResultUri(
                authorization.getReturnUrl(),
                "error",
                normalizedCode,
                failureCode);
    }

    @Override
    @Transactional
    public MarketplaceConnectionResponse verify(
            TenantPrincipal principal,
            String accountId) {
        AccountContext context = requireAccount(principal, accountId);
        String accessToken = encryptionService.decrypt(
                context.credential().getAccessTokenEncrypted());
        MarketplaceConnector.ShopProfile profile = connectorRegistry
                .require(context.marketplace().getMarketplaceCode())
                .getShopProfile(accessToken);
        Instant now = Instant.now();
        String previousStatus = context.account().getConnectionStatus();
        updateShop(context.account(), profile);
        context.account().setConnectionStatus(CONNECTED);
        context.account().setLastVerifiedAt(now);
        context.account().setUpdatedAt(now);
        accountRepository.save(context.account());
        history(
                context.account(),
                previousStatus,
                CONNECTED,
                "MANUAL_VERIFY",
                principal.userId());
        audit(
                principal,
                "MARKETPLACE.VERIFIED",
                "MARKETPLACE_ACCOUNT",
                accountId,
                "SUCCEEDED",
                "{}");
        return toResponse(context.account(), context.marketplace(), context.credential());
    }

    @Override
    @Transactional
    public MarketplaceConnectionResponse refresh(
            TenantPrincipal principal,
            String accountId) {
        AccountContext context = requireAccount(principal, accountId);
        String encryptedRefreshToken = context.credential().getRefreshTokenEncrypted();
        if (encryptedRefreshToken == null || encryptedRefreshToken.isBlank()) {
            throw badRequest(
                    "REFRESH_TOKEN_MISSING",
                    "Kết nối này không có refresh token.");
        }
        MarketplaceConnector.TokenResult token = connectorRegistry
                .require(context.marketplace().getMarketplaceCode())
                .refresh(encryptionService.decrypt(encryptedRefreshToken));
        Instant now = Instant.now();
        String previousStatus = context.account().getConnectionStatus();
        applyToken(context.credential(), token, now);
        credentialRepository.save(context.credential());
        context.account().setExpiresAt(token.accessTokenExpiresAt());
        context.account().setConnectionStatus(CONNECTED);
        context.account().setUpdatedAt(now);
        accountRepository.save(context.account());
        history(
                context.account(),
                previousStatus,
                CONNECTED,
                "TOKEN_REFRESHED",
                principal.userId());
        audit(
                principal,
                "MARKETPLACE.TOKEN_REFRESHED",
                "MARKETPLACE_ACCOUNT",
                accountId,
                "SUCCEEDED",
                "{}");
        return toResponse(context.account(), context.marketplace(), context.credential());
    }

    @Override
    @Transactional
    public void disconnect(TenantPrincipal principal, String accountId) {
        AccountContext context = requireAccount(principal, accountId);
        String previousStatus = context.account().getConnectionStatus();
        try {
            MarketplaceConnector connector =
                    connectorRegistry.require(context.marketplace().getMarketplaceCode());
            connector.revoke(encryptionService.decrypt(
                    context.credential().getAccessTokenEncrypted()));
            if (context.credential().getRefreshTokenEncrypted() != null) {
                connector.revoke(encryptionService.decrypt(
                        context.credential().getRefreshTokenEncrypted()));
            }
        } catch (AuthenticationException ignored) {
            // Local revocation must still complete when the simulator is temporarily down.
        }

        credentialRepository.delete(context.credential());
        Instant now = Instant.now();
        context.account().setConnectionStatus("REVOKED");
        context.account().setDeletedAt(now);
        context.account().setUpdatedAt(now);
        accountRepository.save(context.account());
        history(
                context.account(),
                previousStatus,
                "REVOKED",
                "USER_DISCONNECTED",
                principal.userId());
        audit(
                principal,
                "MARKETPLACE.DISCONNECTED",
                "MARKETPLACE_ACCOUNT",
                accountId,
                "SUCCEEDED",
                "{}");
    }

    private MarketplaceAccountEntity saveConnectedAccount(
            TenantPrincipal principal,
            MarketplaceEntity marketplace,
            MarketplaceConnector.TokenResult token,
            MarketplaceConnector.ShopProfile shop,
            Instant now) {
        MarketplaceAccountEntity account = accountRepository
                .findByMarketplaceIdAndExternalAccountId(
                        marketplace.getId(),
                        shop.externalAccountId())
                .map(existing -> requireSameTenantOwner(existing, principal.tenantId()))
                .orElseGet(() -> newMarketplaceAccount(
                        principal,
                        marketplace,
                        shop,
                        now));
        String previousStatus = account.getConnectionStatus();
        updateShop(account, shop);
        account.setConnectionStatus(CONNECTED);
        account.setAuthorizedAt(now);
        account.setExpiresAt(token.accessTokenExpiresAt());
        account.setLastVerifiedAt(now);
        account.setDeletedAt(null);
        account.setUpdatedAt(now);
        accountRepository.save(account);

        MarketplaceCredentialEntity credential = credentialRepository
                .findByMarketplaceAccountId(account.getId())
                .orElseGet(() -> {
                    MarketplaceCredentialEntity created =
                            new MarketplaceCredentialEntity();
                    created.setId(UUID.randomUUID().toString());
                    created.setMarketplaceAccountId(account.getId());
                    created.setCreatedAt(now);
                    return created;
                });
        credential.setAppKey(
                properties.provider(marketplace.getMarketplaceCode()).clientId());
        applyToken(credential, token, now);
        credentialRepository.save(credential);
        history(
                account,
                previousStatus,
                CONNECTED,
                previousStatus == null ? "INITIAL_AUTHORIZATION" : "REAUTHORIZED",
                principal.userId());
        return account;
    }

    private static MarketplaceAccountEntity requireSameTenantOwner(
            MarketplaceAccountEntity account,
            String tenantId) {
        if (!account.getTenantId().equals(tenantId)) {
            throw new AuthenticationException(
                    HttpStatus.CONFLICT,
                    "SHOP_ALREADY_CONNECTED_TO_ANOTHER_TENANT",
                    "Shop này đã được liên kết với một doanh nghiệp khác.");
        }
        return account;
    }

    private static MarketplaceAccountEntity newMarketplaceAccount(
            TenantPrincipal principal,
            MarketplaceEntity marketplace,
            MarketplaceConnector.ShopProfile shop,
            Instant now) {
        MarketplaceAccountEntity created = new MarketplaceAccountEntity();
        created.setId(UUID.randomUUID().toString());
        created.setTenantId(principal.tenantId());
        created.setMarketplaceId(marketplace.getId());
        created.setExternalAccountId(shop.externalAccountId());
        created.setCreatedByUserId(principal.userId());
        created.setSettingsJson("{}");
        created.setCreatedAt(now);
        return created;
    }

    private void applyToken(
            MarketplaceCredentialEntity credential,
            MarketplaceConnector.TokenResult token,
            Instant now) {
        credential.setAccessTokenEncrypted(encryptionService.encrypt(token.accessToken()));
        credential.setRefreshTokenEncrypted(encryptionService.encrypt(token.refreshToken()));
        credential.setScopesJson(toJsonArray(token.scopes()));
        credential.setEncryptionKeyVersion(encryptionService.keyVersion());
        credential.setAccessTokenExpiresAt(token.accessTokenExpiresAt());
        credential.setRefreshTokenExpiresAt(token.refreshTokenExpiresAt());
        credential.setLastRefreshedAt(now);
        credential.setUpdatedAt(now);
    }

    private static void updateShop(
            MarketplaceAccountEntity account,
            MarketplaceConnector.ShopProfile profile) {
        account.setShopCipher(profile.shopCipher());
        account.setExternalShopName(profile.shopName());
        account.setSiteId(profile.siteId());
        account.setCurrency(profile.currency());
        account.setTimezoneName(profile.timezoneName());
    }

    private AccountContext requireAccount(TenantPrincipal principal, String accountId) {
        MarketplaceAccountEntity account = accountRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(accountId, principal.tenantId())
                .orElseThrow(() -> new AuthenticationException(
                        HttpStatus.NOT_FOUND,
                        "MARKETPLACE_ACCOUNT_NOT_FOUND",
                        "Không tìm thấy kết nối sàn thuộc tenant hiện tại."));
        MarketplaceEntity marketplace = marketplaceRepository.findById(account.getMarketplaceId())
                .orElseThrow(() -> new AuthenticationException(
                        HttpStatus.CONFLICT,
                        "MARKETPLACE_CONFIGURATION_MISSING",
                        "Cấu hình sàn của kết nối không còn tồn tại."));
        MarketplaceCredentialEntity credential = credentialRepository
                .findByMarketplaceAccountId(account.getId())
                .orElseThrow(() -> new AuthenticationException(
                        HttpStatus.CONFLICT,
                        "MARKETPLACE_CREDENTIAL_MISSING",
                        "Kết nối sàn không còn thông tin xác thực."));
        return new AccountContext(account, marketplace, credential);
    }

    private void validateAuthorizationBinding(
            OAuthAuthorizationSessionEntity authorization,
            MarketplaceEntity marketplace,
            TenantPrincipal principal) {
        if (!authorization.getTenantId().equals(principal.tenantId())
                || !authorization.getTenantUserId().equals(principal.userId())
                || !authorization.getMarketplaceId().equals(marketplace.getId())) {
            throw badRequest(
                    "INVALID_OAUTH_STATE",
                    "Phiên liên kết không thuộc tài khoản hoặc sàn hiện tại.");
        }
        if (!"PENDING".equals(authorization.getStatus())) {
            throw badRequest(
                    "OAUTH_STATE_ALREADY_USED",
                    "Phiên liên kết sàn đã được sử dụng.");
        }
    }

    private MarketplaceEntity requireMarketplace(String marketplaceCode) {
        return marketplaceRepository.findByMarketplaceCodeAndActiveTrue(marketplaceCode)
                .orElseThrow(() -> badRequest(
                        "MARKETPLACE_NOT_AVAILABLE",
                        "Sàn chưa được kích hoạt trong hệ thống."));
    }

    private static MarketplaceEntity requireMarketplace(MarketplaceEntity marketplace) {
        if (marketplace == null) {
            throw new AuthenticationException(
                    HttpStatus.CONFLICT,
                    "MARKETPLACE_CONFIGURATION_MISSING",
                    "Cấu hình sàn của kết nối không còn tồn tại.");
        }
        return marketplace;
    }

    private String callbackUri(String marketplaceCode) {
        String slug = "TIKTOK_SHOP".equals(marketplaceCode) ? "tiktok-shop" : "lazada";
        return properties.callbackBaseUrl().replaceAll("/+$", "")
                + "/api/marketplace-connections/callback/" + slug;
    }

    private static URI validateReturnUrl(String returnUrl) {
        try {
            URI uri = URI.create(returnUrl);
            boolean localHost = "localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost());
            boolean localFrontend = "http".equalsIgnoreCase(uri.getScheme())
                    && localHost
                    && uri.getPort() >= 1;
            boolean productionFrontend = "https".equalsIgnoreCase(uri.getScheme())
                    && PRODUCTION_FRONTEND_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1;
            if ((!localFrontend && !productionFrontend)
                    || !"/connect".equals(uri.getPath())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("unsafe return URL");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw badRequest(
                    "INVALID_RETURN_URL",
                    "Địa chỉ quay lại phải là trang /connect được cho phép.");
        }
    }

    private static URI callbackResultUri(
            String returnUrl,
            String status,
            String marketplace,
            String errorCode) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(returnUrl)
                .replaceQueryParam("connection", status)
                .replaceQueryParam("marketplace", marketplace);
        if (errorCode != null) {
            builder.replaceQueryParam("error", errorCode);
        } else {
            builder.replaceQueryParam("error");
        }
        return builder.build().encode().toUri();
    }

    private MarketplaceConnectionResponse toResponse(
            MarketplaceAccountEntity account,
            MarketplaceEntity marketplace,
            MarketplaceCredentialEntity credential) {
        return new MarketplaceConnectionResponse(
                account.getId(),
                marketplace.getMarketplaceCode(),
                marketplace.getMarketplaceName(),
                account.getExternalAccountId(),
                account.getExternalShopName(),
                account.getSiteId(),
                account.getCurrency(),
                account.getTimezoneName(),
                account.getConnectionStatus(),
                account.getAuthorizedAt(),
                credential == null ? account.getExpiresAt()
                        : credential.getAccessTokenExpiresAt(),
                account.getLastVerifiedAt(),
                credential == null ? List.of()
                        : parseJsonArray(credential.getScopesJson()));
    }

    private void history(
            MarketplaceAccountEntity account,
            String fromStatus,
            String toStatus,
            String reasonCode,
            String userId) {
        MarketplaceConnectionHistoryEntity history =
                new MarketplaceConnectionHistoryEntity();
        history.setMarketplaceAccountId(account.getId());
        history.setFromStatus(fromStatus);
        history.setToStatus(toStatus);
        history.setReasonCode(reasonCode);
        history.setDetailsJson("{}");
        history.setChangedByUserId(userId);
        history.setOccurredAt(Instant.now());
        connectionHistoryRepository.save(history);
    }

    private void audit(
            TenantPrincipal principal,
            String action,
            String targetType,
            String targetId,
            String result,
            String metadataJson) {
        SecurityAuditLogEntity log = new SecurityAuditLogEntity();
        log.setTenantId(principal.tenantId());
        log.setActorType("TENANT_USER");
        log.setActorId(principal.userId());
        log.setActionCode(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setResult(result);
        log.setMetadataJson(metadataJson);
        log.setOccurredAt(Instant.now());
        auditLogRepository.save(log);
    }

    private static String normalizeMarketplaceCode(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase();
    }

    private static String toJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"") + "\"")
                .reduce("[", (left, value) ->
                        "[".equals(left) ? left + value : left + "," + value)
                + "]";
    }

    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = JSON_STRING.matcher(json);
        while (matcher.find()) {
            values.add(matcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\"));
        }
        return List.copyOf(values);
    }

    private static AuthenticationException badRequest(String code, String message) {
        return new AuthenticationException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record AccountContext(
            MarketplaceAccountEntity account,
            MarketplaceEntity marketplace,
            MarketplaceCredentialEntity credential) {
    }
}
