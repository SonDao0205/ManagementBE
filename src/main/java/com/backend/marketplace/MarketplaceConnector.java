package com.backend.marketplace;

import java.time.Instant;
import java.util.List;

public interface MarketplaceConnector {

    String marketplaceCode();

    String authorizationUrl(String state, String redirectUri);

    TokenResult exchangeAuthorizationCode(String code, String redirectUri);

    TokenResult refresh(String refreshToken);

    ShopProfile getShopProfile(String accessToken);

    void revoke(String token);

    record TokenResult(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt,
            List<String> scopes) {
    }

    record ShopProfile(
            String externalAccountId,
            String shopCipher,
            String shopName,
            String siteId,
            String currency,
            String timezoneName) {
    }
}
