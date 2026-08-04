package com.backend.dto;

import java.time.Instant;
import java.util.List;

public record AiShopContextResponse(
        String id,
        String marketplaceAccountId,
        String shopName,
        String contextName,
        String mood,
        String assistantName,
        String businessDescription,
        String brandVoice,
        String responseGuidelines,
        List<String> prohibitedTopics,
        String defaultLanguage,
        Integer maxResponseCharacters,
        String defaultKnowledgeBaseId,
        boolean active,
        Instant activatedAt,
        Instant createdAt,
        Instant updatedAt) {
}
