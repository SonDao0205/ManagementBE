package com.backend.marketplace;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.backend.service.AuthenticationException;

@Component
public class MarketplaceConnectorRegistry {

    private final Map<String, MarketplaceConnector> connectors;

    public MarketplaceConnectorRegistry(List<MarketplaceConnector> connectors) {
        this.connectors = connectors.stream().collect(Collectors.toUnmodifiableMap(
                MarketplaceConnector::marketplaceCode,
                Function.identity()));
    }

    public MarketplaceConnector require(String marketplaceCode) {
        MarketplaceConnector connector = connectors.get(marketplaceCode);
        if (connector == null) {
            throw new AuthenticationException(
                    HttpStatus.BAD_REQUEST,
                    "MARKETPLACE_NOT_SUPPORTED",
                    "Sàn chưa được hệ thống hỗ trợ.");
        }
        return connector;
    }
}
