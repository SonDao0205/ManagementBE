package com.backend.marketplace;

import org.springframework.stereotype.Component;

import com.backend.config.MarketplaceProperties;

@Component
public class LazadaMockConnector extends AbstractMockMarketplaceConnector {

    public LazadaMockConnector(MarketplaceProperties properties) {
        super("LAZADA", properties.lazada());
    }
}
