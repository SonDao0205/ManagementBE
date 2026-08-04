package com.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.backend.security.TenantPrincipal;

@SpringBootTest
@AutoConfigureMockMvc
class AiShopContextIntegrationTest {

    private static final String TENANT_ID = "81000000-0000-0000-0000-000000000001";
    private static final String OTHER_TENANT_ID = "81000000-0000-0000-0000-000000000002";
    private static final String USER_ID = "82000000-0000-0000-0000-000000000001";
    private static final String SHOP_ID = "83000000-0000-0000-0000-000000000001";
    private static final String OTHER_SHOP_ID = "83000000-0000-0000-0000-000000000002";
    private static final String MARKETPLACE_ID = "84000000-0000-0000-0000-000000000001";

    @DynamicPropertySource
    static void aiContextDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:omnichannel_ai_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void seed() {
        cleanup();
        jdbcClient.sql("""
                INSERT INTO tenants (id, tenant_code, tenant_name, status)
                VALUES (:id, 'AI_SHOP', 'Shop AI', 'ACTIVE')
                """).param("id", TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO tenants (id, tenant_code, tenant_name, status)
                VALUES (:id, 'OTHER_AI_SHOP', 'Shop khác', 'ACTIVE')
                """).param("id", OTHER_TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO tenant_users (id, tenant_id, email, display_name, status)
                VALUES (:id, :tenantId, 'ai-owner@example.test', 'AI Owner', 'ACTIVE')
                """).param("id", USER_ID).param("tenantId", TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO marketplaces (id, marketplace_code, marketplace_name, is_active)
                VALUES (:id, 'TIKTOK_SHOP', 'TikTok Shop', TRUE)
                """).param("id", MARKETPLACE_ID).update();
        insertShop(SHOP_ID, TENANT_ID, "shop-ai", "Shop AI TikTok");
        insertShop(OTHER_SHOP_ID, OTHER_TENANT_ID, "shop-other", "Shop tenant khác");
    }

    @AfterEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM security_audit_logs WHERE tenant_id IN (:one, :two)")
                .param("one", TENANT_ID).param("two", OTHER_TENANT_ID).update();
        jdbcClient.sql("DELETE FROM ai_shop_contexts WHERE tenant_id IN (:one, :two)")
                .param("one", TENANT_ID).param("two", OTHER_TENANT_ID).update();
        jdbcClient.sql("DELETE FROM marketplace_accounts WHERE id IN (:one, :two)")
                .param("one", SHOP_ID).param("two", OTHER_SHOP_ID).update();
        jdbcClient.sql("DELETE FROM marketplaces WHERE id = :id")
                .param("id", MARKETPLACE_ID).update();
        jdbcClient.sql("DELETE FROM tenant_users WHERE id = :id")
                .param("id", USER_ID).update();
        jdbcClient.sql("DELETE FROM tenants WHERE id IN (:one, :two)")
                .param("one", TENANT_ID).param("two", OTHER_TENANT_ID).update();
    }

    @Test
    void crudActivationAndShopIsolationWorkTogether() throws Exception {
        String firstId = create("Mood thân thiện", "FRIENDLY", "Mây");
        String secondId = create("Mood chuyên nghiệp", "PROFESSIONAL", "An");

        mockMvc.perform(patch("/api/ai-contexts/{id}/activation", firstId)
                        .with(authentication(aiAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(patch("/api/ai-contexts/{id}/activation", secondId)
                        .with(authentication(aiAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/ai-contexts")
                        .with(authentication(aiAuthentication()))
                        .queryParam("shopId", SHOP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(secondId))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].active").value(false));

        mockMvc.perform(put("/api/ai-contexts/{id}", secondId)
                        .with(authentication(aiAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Mood chuyên nghiệp", "CONCISE", "An")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mood").value("CONCISE"));

        mockMvc.perform(get("/api/ai-contexts")
                        .with(authentication(aiAuthentication()))
                        .queryParam("shopId", OTHER_SHOP_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MARKETPLACE_SHOP_NOT_FOUND"));

        mockMvc.perform(delete("/api/ai-contexts/{id}", secondId)
                        .with(authentication(aiAuthentication()))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    private String create(String name, String mood, String assistant) throws Exception {
        String response = mockMvc.perform(post("/api/ai-contexts")
                        .with(authentication(aiAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, mood, assistant)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.marketplaceAccountId").value(SHOP_ID))
                .andExpect(jsonPath("$.active").value(false))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.id");
    }

    private String body(String name, String mood, String assistant) {
        return """
                {
                  "marketplaceAccountId":"%s",
                  "contextName":"%s",
                  "mood":"%s",
                  "assistantName":"%s",
                  "businessDescription":"Shop thời trang",
                  "brandVoice":"Thân thiện và rõ ràng",
                  "responseGuidelines":"Xưng em và trả lời ngắn gọn",
                  "prohibitedTopics":["tư vấn y tế"],
                  "defaultLanguage":"vi",
                  "maxResponseCharacters":1200,
                  "defaultKnowledgeBaseId":null
                }
                """.formatted(SHOP_ID, name, mood, assistant);
    }

    private void insertShop(String id, String tenantId, String externalId, String name) {
        jdbcClient.sql("""
                INSERT INTO marketplace_accounts (
                  id, tenant_id, marketplace_id, external_account_id, external_shop_name,
                  site_id, currency, timezone_name, connection_status, settings_json,
                  created_at, updated_at
                ) VALUES (
                  :id, :tenantId, :marketplaceId, :externalId, :name,
                  'VN', 'VND', 'Asia/Ho_Chi_Minh', 'CONNECTED', '{}',
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("marketplaceId", MARKETPLACE_ID)
                .param("externalId", externalId)
                .param("name", name)
                .update();
    }

    private Authentication aiAuthentication() {
        TenantPrincipal principal = new TenantPrincipal(
                "session-ai",
                USER_ID,
                "ai-owner@example.test",
                "AI Owner",
                null,
                TENANT_ID,
                "AI_SHOP",
                "Shop AI",
                List.of("TENANT_MANAGER"),
                List.of("AI.CONFIGURE"),
                false,
                java.time.Instant.now().plusSeconds(3600));
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(
                        new SimpleGrantedAuthority("SESSION_AUTHENTICATED"),
                        new SimpleGrantedAuthority("AI.CONFIGURE")));
    }
}
