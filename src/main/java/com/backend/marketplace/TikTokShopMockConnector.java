package com.backend.marketplace;

import org.springframework.stereotype.Component;

import com.backend.config.MarketplaceProperties;

@Component
public class TikTokShopMockConnector extends AbstractMockMarketplaceConnector {

    public TikTokShopMockConnector(MarketplaceProperties properties) {
        super("TIKTOK_SHOP", properties.tiktok());
    }
}
