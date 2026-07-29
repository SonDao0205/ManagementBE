package com.backend.service;

import java.net.URI;
import java.util.List;

import com.backend.dto.MarketplaceAuthorizationResponse;
import com.backend.dto.MarketplaceAuthorizeRequest;
import com.backend.dto.MarketplaceConnectionResponse;
import com.backend.security.TenantPrincipal;

public interface MarketplaceConnectionService {

    List<MarketplaceConnectionResponse> list(TenantPrincipal principal);

    MarketplaceAuthorizationResponse beginAuthorization(
            TenantPrincipal principal,
            MarketplaceAuthorizeRequest request);

    URI completeAuthorization(
            TenantPrincipal principal,
            String marketplaceCode,
            String authorizationCode,
            String state);

    URI denyAuthorization(
            TenantPrincipal principal,
            String marketplaceCode,
            String state);

    MarketplaceConnectionResponse verify(TenantPrincipal principal, String accountId);

    MarketplaceConnectionResponse refresh(TenantPrincipal principal, String accountId);

    void disconnect(TenantPrincipal principal, String accountId);
}
