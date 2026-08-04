package com.backend.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "ai_shop_contexts")
public class AiShopContextEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "marketplace_account_id", nullable = false, length = 36)
    private String marketplaceAccountId;

    @Column(name = "context_name", nullable = false, length = 150)
    private String contextName;

    @Column(nullable = false, length = 30)
    private String mood;

    @Column(name = "assistant_name", nullable = false, length = 100)
    private String assistantName;

    @Column(name = "business_description", nullable = false, columnDefinition = "TEXT")
    private String businessDescription;

    @Column(name = "brand_voice", nullable = false, length = 1000)
    private String brandVoice;

    @Column(name = "response_guidelines", nullable = false, columnDefinition = "TEXT")
    private String responseGuidelines;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prohibited_topics_json", nullable = false, columnDefinition = "jsonb")
    private String prohibitedTopicsJson;

    @Column(name = "default_language", nullable = false, length = 20)
    private String defaultLanguage;

    @Column(name = "max_response_characters", nullable = false)
    private Integer maxResponseCharacters;

    @Column(name = "default_knowledge_base_id", length = 36)
    private String defaultKnowledgeBaseId;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_by_user_id", nullable = false, length = 36)
    private String createdByUserId;

    @Column(name = "activated_by_user_id", length = 36)
    private String activatedByUserId;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
