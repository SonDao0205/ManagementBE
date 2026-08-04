package com.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.AiShopContextRequest;
import com.backend.dto.AiShopContextResponse;
import com.backend.entity.AiShopContextEntity;
import com.backend.entity.MarketplaceAccountEntity;
import com.backend.entity.SecurityAuditLogEntity;
import com.backend.repository.AiShopContextRepository;
import com.backend.repository.MarketplaceAccountRepository;
import com.backend.repository.SecurityAuditLogRepository;
import com.backend.security.TenantPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AiShopContextService {

    private final AiShopContextRepository contextRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final SecurityAuditLogRepository auditLogRepository;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiShopContextService(
            AiShopContextRepository contextRepository,
            MarketplaceAccountRepository marketplaceAccountRepository,
            SecurityAuditLogRepository auditLogRepository,
            JdbcClient jdbcClient) {
        this.contextRepository = contextRepository;
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.auditLogRepository = auditLogRepository;
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public List<AiShopContextResponse> list(TenantPrincipal principal, String shopId) {
        MarketplaceAccountEntity shop = requireShop(principal.tenantId(), shopId);
        return contextRepository
                .findByTenantIdAndMarketplaceAccountIdAndDeletedAtIsNullOrderByActiveDescUpdatedAtDesc(
                        principal.tenantId(), shopId)
                .stream()
                .map(context -> toResponse(context, shop.getExternalShopName()))
                .toList();
    }

    @Transactional
    public AiShopContextResponse create(TenantPrincipal principal, AiShopContextRequest request) {
        MarketplaceAccountEntity shop = requireShop(
                principal.tenantId(), request.marketplaceAccountId());
        String name = normalizedRequired(request.contextName());
        if (contextRepository
                .existsByTenantIdAndMarketplaceAccountIdAndContextNameIgnoreCaseAndDeletedAtIsNull(
                        principal.tenantId(), shop.getId(), name)) {
            throw conflict("AI_CONTEXT_NAME_ALREADY_EXISTS",
                    "Shop này đã có một ngữ cảnh cùng tên.");
        }
        validateKnowledgeBase(principal.tenantId(), request.defaultKnowledgeBaseId());

        Instant now = Instant.now();
        AiShopContextEntity context = new AiShopContextEntity();
        context.setId(UUID.randomUUID().toString());
        context.setTenantId(principal.tenantId());
        context.setMarketplaceAccountId(shop.getId());
        apply(context, request);
        context.setActive(false);
        context.setCreatedByUserId(principal.userId());
        context.setCreatedAt(now);
        context.setUpdatedAt(now);
        contextRepository.saveAndFlush(context);
        audit(principal, "AI_CONTEXT_CREATED", context, Map.of("shopId", shop.getId()));
        return toResponse(context, shop.getExternalShopName());
    }

    @Transactional
    public AiShopContextResponse update(
            TenantPrincipal principal,
            String contextId,
            AiShopContextRequest request) {
        AiShopContextEntity context = requireContext(principal.tenantId(), contextId);
        if (!context.getMarketplaceAccountId().equals(request.marketplaceAccountId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI_CONTEXT_SHOP_IMMUTABLE",
                    "Không thể chuyển ngữ cảnh sang shop khác. Hãy tạo một ngữ cảnh mới cho shop đó.");
        }
        MarketplaceAccountEntity shop = requireShop(
                principal.tenantId(), context.getMarketplaceAccountId());
        String name = normalizedRequired(request.contextName());
        if (contextRepository
                .existsByTenantIdAndMarketplaceAccountIdAndContextNameIgnoreCaseAndIdNotAndDeletedAtIsNull(
                        principal.tenantId(), shop.getId(), name, contextId)) {
            throw conflict("AI_CONTEXT_NAME_ALREADY_EXISTS",
                    "Shop này đã có một ngữ cảnh cùng tên.");
        }
        validateKnowledgeBase(principal.tenantId(), request.defaultKnowledgeBaseId());
        apply(context, request);
        context.setUpdatedAt(Instant.now());
        contextRepository.saveAndFlush(context);
        audit(principal, "AI_CONTEXT_UPDATED", context, Map.of("shopId", shop.getId()));
        return toResponse(context, shop.getExternalShopName());
    }

    @Transactional
    public AiShopContextResponse setActive(
            TenantPrincipal principal,
            String contextId,
            boolean active) {
        AiShopContextEntity context = requireContext(principal.tenantId(), contextId);
        MarketplaceAccountEntity shop = requireShop(
                principal.tenantId(), context.getMarketplaceAccountId());
        Instant now = Instant.now();

        contextRepository.deactivateAllForShop(
                principal.tenantId(), context.getMarketplaceAccountId(), now);
        context = requireContext(principal.tenantId(), contextId);
        context.setActive(active);
        context.setActivatedByUserId(active ? principal.userId() : null);
        context.setActivatedAt(active ? now : null);
        context.setUpdatedAt(now);
        contextRepository.saveAndFlush(context);
        audit(
                principal,
                active ? "AI_CONTEXT_ACTIVATED" : "AI_CONTEXT_DEACTIVATED",
                context,
                Map.of("shopId", shop.getId(), "active", active));
        return toResponse(context, shop.getExternalShopName());
    }

    @Transactional
    public void delete(TenantPrincipal principal, String contextId) {
        AiShopContextEntity context = requireContext(principal.tenantId(), contextId);
        Instant now = Instant.now();
        context.setActive(false);
        context.setActivatedByUserId(null);
        context.setActivatedAt(null);
        context.setDeletedAt(now);
        context.setUpdatedAt(now);
        contextRepository.saveAndFlush(context);
        audit(
                principal,
                "AI_CONTEXT_DELETED",
                context,
                Map.of("shopId", context.getMarketplaceAccountId()));
    }

    private void apply(AiShopContextEntity context, AiShopContextRequest request) {
        context.setContextName(normalizedRequired(request.contextName()));
        context.setMood(request.mood().trim().toUpperCase(Locale.ROOT));
        context.setAssistantName(normalizedRequired(request.assistantName()));
        context.setBusinessDescription(normalizedOptional(request.businessDescription()));
        context.setBrandVoice(normalizedRequired(request.brandVoice()));
        context.setResponseGuidelines(normalizedOptional(request.responseGuidelines()));
        context.setProhibitedTopicsJson(writeJson(normalizeTopics(request.prohibitedTopics())));
        context.setDefaultLanguage(request.defaultLanguage().trim());
        context.setMaxResponseCharacters(
                request.maxResponseCharacters() == null ? 1200 : request.maxResponseCharacters());
        context.setDefaultKnowledgeBaseId(normalizedNullable(request.defaultKnowledgeBaseId()));
    }

    private MarketplaceAccountEntity requireShop(String tenantId, String shopId) {
        return marketplaceAccountRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(shopId, tenantId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MARKETPLACE_SHOP_NOT_FOUND",
                        "Không tìm thấy shop thuộc tài khoản hiện tại."));
    }

    private AiShopContextEntity requireContext(String tenantId, String contextId) {
        return contextRepository.findByIdAndTenantIdAndDeletedAtIsNull(contextId, tenantId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "AI_CONTEXT_NOT_FOUND",
                        "Không tìm thấy ngữ cảnh AI."));
    }

    private void validateKnowledgeBase(String tenantId, String knowledgeBaseId) {
        String normalized = normalizedNullable(knowledgeBaseId);
        if (normalized == null) {
            return;
        }
        int count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM knowledge_bases
                WHERE id = :id AND tenant_id = :tenantId AND status = 'ACTIVE'
                """)
                .param("id", normalized)
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
        if (count == 0) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "KNOWLEDGE_BASE_NOT_FOUND",
                    "Không tìm thấy kho tri thức đang hoạt động của tài khoản hiện tại.");
        }
    }

    private AiShopContextResponse toResponse(AiShopContextEntity context, String shopName) {
        return new AiShopContextResponse(
                context.getId(),
                context.getMarketplaceAccountId(),
                shopName,
                context.getContextName(),
                context.getMood(),
                context.getAssistantName(),
                context.getBusinessDescription(),
                context.getBrandVoice(),
                context.getResponseGuidelines(),
                readTopics(context.getProhibitedTopicsJson()),
                context.getDefaultLanguage(),
                context.getMaxResponseCharacters(),
                context.getDefaultKnowledgeBaseId(),
                context.isActive(),
                context.getActivatedAt(),
                context.getCreatedAt(),
                context.getUpdatedAt());
    }

    private void audit(
            TenantPrincipal principal,
            String action,
            AiShopContextEntity context,
            Map<String, Object> metadata) {
        SecurityAuditLogEntity log = new SecurityAuditLogEntity();
        log.setTenantId(principal.tenantId());
        log.setActorType("TENANT_USER");
        log.setActorId(principal.userId());
        log.setActionCode(action);
        log.setTargetType("AI_SHOP_CONTEXT");
        log.setTargetId(context.getId());
        log.setResult("SUCCEEDED");
        log.setMetadataJson(writeJson(metadata));
        log.setOccurredAt(Instant.now());
        auditLogRepository.save(log);
    }

    private List<String> normalizeTopics(List<String> topics) {
        if (topics == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String topic : topics) {
            String normalized = topic == null ? "" : topic.trim();
            if (!normalized.isEmpty()
                    && !unique.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "DUPLICATE_PROHIBITED_TOPIC",
                        "Danh sách chủ đề cấm không được có giá trị trùng nhau.");
            }
        }
        List<String> result = new ArrayList<>();
        for (String normalized : unique) {
            topics.stream()
                    .map(value -> value == null ? "" : value.trim())
                    .filter(value -> value.equalsIgnoreCase(normalized))
                    .findFirst()
                    .ifPresent(result::add);
        }
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize AI context JSON", exception);
        }
    }

    private List<String> readTopics(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    private static String normalizedRequired(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizedOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizedNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
