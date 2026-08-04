package com.backend;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.security.TenantPrincipal;
import com.backend.service.AiBackendClient;

@SpringBootTest
@AutoConfigureMockMvc
class AiConversationIntegrationTest {

    private static final String TENANT_ID = "91000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "92000000-0000-0000-0000-000000000001";
    private static final String CONVERSATION_ID = "93000000-0000-0000-0000-000000000001";
    private static final String MESSAGE_ID = "94000000-0000-0000-0000-000000000001";

    @DynamicPropertySource
    static void aiConversationDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:omnichannel_ai_gateway;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private AiBackendClient aiBackendClient;

    @BeforeEach
    void seed() {
        cleanup();
        jdbcClient.sql("""
                INSERT INTO tenants (id, tenant_code, tenant_name, status)
                VALUES (:id, 'AI_GATEWAY', 'AI Gateway', 'ACTIVE')
                """).param("id", TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO tenant_users (id, tenant_id, email, display_name, status)
                VALUES (:id, :tenantId, 'ai-agent@example.test', 'AI Agent', 'ACTIVE')
                """).param("id", USER_ID).param("tenantId", TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO conversations (id, tenant_id, ai_mode)
                VALUES (:id, :tenantId, 'SUGGEST_ONLY')
                """).param("id", CONVERSATION_ID).param("tenantId", TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO messages (id, tenant_id, conversation_id, direction, text_content)
                VALUES (:id, :tenantId, :conversationId, 'INBOUND', 'Đơn hàng của tôi đâu?')
                """)
                .param("id", MESSAGE_ID)
                .param("tenantId", TENANT_ID)
                .param("conversationId", CONVERSATION_ID)
                .update();
    }

    @AfterEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM messages WHERE id = :id").param("id", MESSAGE_ID).update();
        jdbcClient.sql("DELETE FROM conversations WHERE id = :id")
                .param("id", CONVERSATION_ID).update();
        jdbcClient.sql("DELETE FROM tenant_users WHERE id = :id").param("id", USER_ID).update();
        jdbcClient.sql("DELETE FROM tenants WHERE id = :id").param("id", TENANT_ID).update();
    }

    @Test
    void suggestionUsesAuthenticatedTenantUserAndNeverAcceptsTenantFromBrowser() throws Exception {
        when(aiBackendClient.createSuggestion(
                TENANT_ID, USER_ID, CONVERSATION_ID, MESSAGE_ID, "request-12345"))
                .thenReturn(Map.of(
                        "id", "95000000-0000-0000-0000-000000000001",
                        "status", "GENERATED"));

        mockMvc.perform(post("/api/ai/conversations/{id}/suggestions", CONVERSATION_ID)
                        .with(authentication(aiAuthentication("AI.SUGGEST")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"triggerMessageId":"%s","requestId":"request-12345"}
                                """.formatted(MESSAGE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATED"));

        verify(aiBackendClient).createSuggestion(
                TENANT_ID, USER_ID, CONVERSATION_ID, MESSAGE_ID, "request-12345");
    }

    @Test
    void modeUpdateIsTenantScopedAndRequiresSuggestPermission() throws Exception {
        mockMvc.perform(patch("/api/ai/conversations/{id}/mode", CONVERSATION_ID)
                        .with(authentication(aiAuthentication("AI.SUGGEST")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiMode").value("AUTO"));

        String storedMode = jdbcClient.sql(
                "SELECT ai_mode FROM conversations WHERE id = :id")
                .param("id", CONVERSATION_ID)
                .query(String.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(storedMode).isEqualTo("AUTO");

        mockMvc.perform(patch("/api/ai/conversations/{id}/mode", CONVERSATION_ID)
                        .with(authentication(aiAuthentication("ORDER.READ")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"HUMAN_ONLY\"}"))
                .andExpect(status().isForbidden());
    }

    private Authentication aiAuthentication(String permission) {
        TenantPrincipal principal = new TenantPrincipal(
                "session-ai-gateway", USER_ID, "ai-agent@example.test", "AI Agent", null,
                TENANT_ID, "AI_GATEWAY", "AI Gateway", List.of("CS_AGENT"),
                List.of(permission), false, Instant.now().plusSeconds(3600));
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(
                        new SimpleGrantedAuthority("SESSION_AUTHENTICATED"),
                        new SimpleGrantedAuthority(permission)));
    }
}
