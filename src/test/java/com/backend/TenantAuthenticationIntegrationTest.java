package com.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;

import com.jayway.jsonpath.JsonPath;
import com.backend.service.ApiException;
import com.backend.service.StaffCredentialEmailService;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class TenantAuthenticationIntegrationTest {

    private static final String TENANT_ID = "10000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "20000000-0000-0000-0000-000000000001";
    private static final String ROLE_ID = "30000000-0000-0000-0000-000000000001";
    private static final String PERMISSION_ID = "40000000-0000-0000-0000-000000000001";
    private static final HttpServer MARKETPLACE_SERVER = startMarketplaceServer();

    @DynamicPropertySource
    static void marketplaceProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://127.0.0.1:" + MARKETPLACE_SERVER.getAddress().getPort();
        registry.add("app.marketplace.tiktok.base-url", () -> baseUrl);
        registry.add("app.marketplace.tiktok.client-id", () -> "omni-tiktok-local");
        registry.add(
                "app.marketplace.tiktok.client-secret",
                () -> java.util.UUID.randomUUID().toString());
        registry.add("app.marketplace.lazada.base-url", () -> baseUrl);
        registry.add("app.marketplace.lazada.client-id", () -> "omni-lazada-local");
        registry.add(
                "app.marketplace.lazada.client-secret",
                () -> java.util.UUID.randomUUID().toString());
    }

    @AfterAll
    static void stopMarketplaceServer() {
        MARKETPLACE_SERVER.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private StaffCredentialEmailService staffCredentialEmailService;

    @BeforeEach
    void seedTenantLogin() {
        jdbcClient.sql("DELETE FROM oauth_authorization_sessions").update();
        jdbcClient.sql("DELETE FROM marketplace_connection_history").update();
        jdbcClient.sql("DELETE FROM marketplace_credentials").update();
        jdbcClient.sql("DELETE FROM marketplace_accounts").update();
        jdbcClient.sql("DELETE FROM marketplaces").update();
        jdbcClient.sql("DELETE FROM security_audit_logs").update();
        jdbcClient.sql("DELETE FROM login_sessions").update();
        jdbcClient.sql("DELETE FROM tenant_password_history").update();
        jdbcClient.sql("DELETE FROM tenant_user_roles").update();
        jdbcClient.sql("DELETE FROM role_permissions").update();
        jdbcClient.sql("DELETE FROM permissions").update();
        jdbcClient.sql("DELETE FROM roles").update();
        jdbcClient.sql("DELETE FROM tenant_user_credentials").update();
        jdbcClient.sql("DELETE FROM tenant_users").update();
        jdbcClient.sql("DELETE FROM tenants").update();

        jdbcClient.sql("""
                INSERT INTO tenants (id, tenant_code, tenant_name, status)
                VALUES (:id, 'SHOP_DEMO', 'Cửa hàng Demo', 'ACTIVE')
                """).param("id", TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO tenant_users
                  (id, tenant_id, email, display_name, status)
                VALUES
                  (:id, :tenantId, 'owner@demo.vn', 'Nguyễn Chủ Shop', 'ACTIVE')
                """)
                .param("id", USER_ID)
                .param("tenantId", TENANT_ID)
                .update();
        jdbcClient.sql("""
                INSERT INTO tenant_user_credentials
                  (tenant_user_id, password_hash, password_algorithm,
                   must_change_password, failed_login_count, credential_version)
                VALUES
                  (:userId, :passwordHash, 'ARGON2ID', TRUE, 0, 1)
                """)
                .param("userId", USER_ID)
                .param("passwordHash", passwordEncoder.encode("Tenant@123"))
                .update();
        jdbcClient.sql("""
                INSERT INTO roles (id, tenant_id, tenant_scope_key, role_code)
                VALUES (:id, :tenantId, :tenantId, 'TENANT_MANAGER')
                """)
                .param("id", ROLE_ID)
                .param("tenantId", TENANT_ID)
                .update();
        jdbcClient.sql("""
                INSERT INTO permissions (id, permission_code)
                VALUES (:id, 'orders.read')
                """).param("id", PERMISSION_ID).update();
        jdbcClient.sql("""
                INSERT INTO role_permissions (role_id, permission_id)
                VALUES (:roleId, :permissionId)
                """)
                .param("roleId", ROLE_ID)
                .param("permissionId", PERMISSION_ID)
                .update();
        jdbcClient.sql("""
                INSERT INTO tenant_user_roles
                  (tenant_user_id, tenant_id, role_id, role_scope_key)
                VALUES (:userId, :tenantId, :roleId, :tenantId)
                """)
                .param("userId", USER_ID)
                .param("tenantId", TENANT_ID)
                .param("roleId", ROLE_ID)
                .update();
    }

    @Test
    void loginReturnsTenantAuthoritiesAndUsesHashedOpaqueSession() throws Exception {
        Csrf csrf = getCsrf();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "OWNER@DEMO.VN",
                                  "password": "Tenant@123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("omni_tenant_session", true))
                .andExpect(jsonPath("$.user.id").value(USER_ID))
                .andExpect(jsonPath("$.tenant.code").value("SHOP_DEMO"))
                .andExpect(jsonPath("$.roles[0]").value("TENANT_MANAGER"))
                .andExpect(jsonPath("$.permissions[0]").value("orders.read"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();

        Cookie sessionCookie = loginResult.getResponse().getCookie("omni_tenant_session");
        assertThat(sessionCookie).isNotNull();
        String storedHash = jdbcClient.sql(
                        "SELECT session_token_hash FROM login_sessions WHERE tenant_user_id = :userId")
                .param("userId", USER_ID)
                .query(String.class)
                .single();
        assertThat(storedHash)
                .hasSize(64)
                .doesNotContain(sessionCookie.getValue());

        String initialStage = jdbcClient.sql(
                        "SELECT auth_stage FROM login_sessions WHERE tenant_user_id = :userId")
                .param("userId", USER_ID)
                .query(String.class)
                .single();
        assertThat(initialStage).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("owner@demo.vn"))
                .andExpect(jsonPath("$.tenant.id").value(TENANT_ID))
                .andExpect(jsonPath("$.roles[0]").value("TENANT_MANAGER"))
                .andExpect(jsonPath("$.permissions[0]").value("orders.read"));

        mockMvc.perform(get("/api/restricted-test").cookie(sessionCookie))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/change-password")
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Tenant@123",
                                  "newPassword": "NewTenant@123",
                                  "confirmPassword": "NewTenant@123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));

        String changedHash = jdbcClient.sql("""
                        SELECT password_hash
                        FROM tenant_user_credentials
                        WHERE tenant_user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(String.class)
                .single();
        assertThat(passwordEncoder.matches("NewTenant@123", changedHash)).isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM tenant_password_history
                        WHERE tenant_user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT auth_stage
                        FROM login_sessions
                        WHERE tenant_user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(String.class)
                .single()).isEqualTo("AUTHENTICATED");

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void loginValidatesEmailAndPasswordBeforeAuthentication() throws Exception {
        Csrf csrf = getCsrf();

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "email-khong-hop-le",
                                  "password": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email")
                        .value("Email không đúng định dạng."))
                .andExpect(jsonPath("$.fieldErrors.password")
                        .value("Vui lòng nhập mật khẩu."));

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM login_sessions")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void staffCreationGeneratesServerPasswordEmailsItAndRollsBackWhenDeliveryFails() throws Exception {
        jdbcClient.sql("""
                INSERT INTO roles (id, tenant_id, tenant_scope_key, role_code)
                VALUES ('30000000-0000-0000-0000-000000000002', :tenantId, :tenantId, 'CS_AGENT')
                """)
                .param("tenantId", TENANT_ID)
                .update();

        Csrf csrf = getCsrf();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@demo.vn","password":"Tenant@123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = loginResult.getResponse().getCookie("omni_tenant_session");
        mockMvc.perform(post("/api/auth/change-password")
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword":"Tenant@123",
                                  "newPassword":"NewTenant@123",
                                  "confirmPassword":"NewTenant@123"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/staff")
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"agent@demo.vn",
                                  "displayName":"Nhân viên CSKH",
                                  "phoneNumber":"0912345678"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("agent@demo.vn"))
                .andExpect(jsonPath("$.password").doesNotExist());

        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(staffCredentialEmailService).sendTemporaryPassword(
                eq("agent@demo.vn"),
                eq("Nhân viên CSKH"),
                eq("Cửa hàng Demo"),
                passwordCaptor.capture());
        String staffId = jdbcClient.sql(
                        "SELECT id FROM tenant_users WHERE email = 'agent@demo.vn'")
                .query(String.class)
                .single();
        String passwordHash = jdbcClient.sql("""
                        SELECT password_hash FROM tenant_user_credentials
                        WHERE tenant_user_id = :staffId
                        """)
                .param("staffId", staffId)
                .query(String.class)
                .single();
        assertThat(passwordCaptor.getValue()).hasSize(20);
        assertThat(passwordEncoder.matches(passwordCaptor.getValue(), passwordHash)).isTrue();

        doThrow(new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "STAFF_EMAIL_DELIVERY_FAILED",
                "Không thể gửi mật khẩu đến email nhân viên. Tài khoản chưa được tạo."))
                .when(staffCredentialEmailService)
                .sendTemporaryPassword(
                        eq("unreachable@demo.vn"), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/staff")
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"unreachable@demo.vn",
                                  "displayName":"Email không nhận được"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STAFF_EMAIL_DELIVERY_FAILED"));
        assertThat(jdbcClient.sql(
                        "SELECT COUNT(*) FROM tenant_users WHERE email = 'unreachable@demo.vn'")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void meRejectsRevokedAndExpiredTenantSessions() throws Exception {
        Csrf csrf = getCsrf();
        MvcResult revokedLogin = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@demo.vn",
                                  "password": "Tenant@123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Cookie revokedCookie =
                revokedLogin.getResponse().getCookie("omni_tenant_session");

        jdbcClient.sql("""
                UPDATE login_sessions
                SET revoked_at = CURRENT_TIMESTAMP
                WHERE tenant_user_id = :userId
                """)
                .param("userId", USER_ID)
                .update();

        mockMvc.perform(get("/api/auth/me").cookie(revokedCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        MvcResult expiredLogin = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@demo.vn",
                                  "password": "Tenant@123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Cookie expiredCookie =
                expiredLogin.getResponse().getCookie("omni_tenant_session");

        jdbcClient.sql("""
                UPDATE login_sessions
                SET expires_at = TIMESTAMP '2000-01-01 00:00:00'
                WHERE tenant_user_id = :userId
                  AND revoked_at IS NULL
                """)
                .param("userId", USER_ID)
                .update();

        mockMvc.perform(get("/api/auth/me").cookie(expiredCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void invalidPasswordIsRejectedAndIncrementsFailureCounter() throws Exception {
        Csrf csrf = getCsrf();
        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@demo.vn",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        Integer failedCount = jdbcClient.sql("""
                        SELECT failed_login_count
                        FROM tenant_user_credentials
                        WHERE tenant_user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(Integer.class)
                .single();
        assertThat(failedCount).isEqualTo(1);
    }

    @Test
    void firstLoginPasswordChangeRejectsWrongCurrentPassword() throws Exception {
        Csrf csrf = getCsrf();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@demo.vn",
                                  "password": "Tenant@123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = loginResult.getResponse().getCookie("omni_tenant_session");

        mockMvc.perform(post("/api/auth/change-password")
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "WrongCurrent@123",
                                  "newPassword": "AnotherTenant@123",
                                  "confirmPassword": "AnotherTenant@123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));

        assertThat(jdbcClient.sql("""
                        SELECT must_change_password
                        FROM tenant_user_credentials
                        WHERE tenant_user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT auth_stage
                        FROM login_sessions
                        WHERE tenant_user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(String.class)
                .single()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    void marketplaceOAuthStoresEncryptedTokensAndSupportsLifecycle() throws Exception {
        seedMarketplacePermissions();
        jdbcClient.sql("""
                INSERT INTO marketplaces
                  (id, marketplace_code, marketplace_name, mock_base_url, is_active)
                VALUES
                  ('50000000-0000-0000-0000-000000000001',
                   'TIKTOK_SHOP', 'TikTok Shop', 'http://127.0.0.1', TRUE)
                """).update();

        Csrf csrf = getCsrf();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@demo.vn",
                                  "password": "Tenant@123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = loginResult.getResponse().getCookie("omni_tenant_session");

        mockMvc.perform(post("/api/auth/change-password")
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Tenant@123",
                                  "newPassword": "NewTenant@123",
                                  "confirmPassword": "NewTenant@123"
                                }
                                """))
                .andExpect(status().isOk());

        MvcResult authorizeResult = mockMvc.perform(
                        post("/api/marketplace-connections/authorize")
                                .cookie(csrf.cookie(), sessionCookie)
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "marketplace": "TIKTOK_SHOP",
                                          "returnUrl": "http://localhost:5173/connect"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketplace").value("TIKTOK_SHOP"))
                .andReturn();
        String authorizationUrl = JsonPath.read(
                authorizeResult.getResponse().getContentAsString(),
                "$.authorizationUrl");
        String state = queryParameter(authorizationUrl, "state");
        assertThat(state).isNotBlank();

        mockMvc.perform(get("/api/marketplace-connections/callback/tiktok-shop")
                        .cookie(sessionCookie)
                        .queryParam("code", "integration-authorization-code")
                        .queryParam("state", state))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("Location"))
                        .contains("connection=success")
                        .contains("marketplace=TIKTOK_SHOP"));

        String accountId = jdbcClient.sql("""
                        SELECT id
                        FROM marketplace_accounts
                        WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", TENANT_ID)
                .query(String.class)
                .single();
        String encryptedAccessToken = jdbcClient.sql("""
                        SELECT access_token_encrypted
                        FROM marketplace_credentials
                        WHERE marketplace_account_id = :accountId
                        """)
                .param("accountId", accountId)
                .query(String.class)
                .single();
        assertThat(encryptedAccessToken)
                .startsWith("v1:")
                .doesNotContain("integration-access-token");

        mockMvc.perform(get("/api/marketplace-connections").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shopName").value("TikTok Integration Shop"))
                .andExpect(jsonPath("$[0].scopes[0]").value("product.read"));

        mockMvc.perform(get("/api/marketplace-connections/callback/tiktok-shop")
                        .cookie(sessionCookie)
                        .queryParam("code", "integration-authorization-code")
                        .queryParam("state", state))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_STATE_ALREADY_USED"));

        mockMvc.perform(post("/api/marketplace-connections/{accountId}/refresh", accountId)
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"));

        mockMvc.perform(post("/api/marketplace-connections/{accountId}/verify", accountId)
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalAccountId").value("tts-integration-shop"));

        mockMvc.perform(delete("/api/marketplace-connections/{accountId}", accountId)
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/marketplace-connections").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM marketplace_credentials
                        WHERE marketplace_account_id = :accountId
                        """)
                .param("accountId", accountId)
                .query(Integer.class)
                .single()).isZero();

        MvcResult reconnectAuthorizeResult = mockMvc.perform(
                        post("/api/marketplace-connections/authorize")
                                .cookie(csrf.cookie(), sessionCookie)
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "marketplace": "TIKTOK_SHOP",
                                          "returnUrl": "http://localhost:5173/connect"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andReturn();
        String reconnectAuthorizationUrl = JsonPath.read(
                reconnectAuthorizeResult.getResponse().getContentAsString(),
                "$.authorizationUrl");
        String reconnectState = queryParameter(reconnectAuthorizationUrl, "state");
        mockMvc.perform(get("/api/marketplace-connections/callback/tiktok-shop")
                        .cookie(sessionCookie)
                        .queryParam("code", "reconnect-authorization-code")
                        .queryParam("state", reconnectState))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("Location"))
                        .contains("connection=success"));
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM marketplace_accounts
                        WHERE tenant_id = :tenantId
                          AND marketplace_id =
                              '50000000-0000-0000-0000-000000000001'
                          AND external_account_id = 'tts-integration-shop'
                        """)
                .param("tenantId", TENANT_ID)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT connection_status
                        FROM marketplace_accounts
                        WHERE id = :accountId
                        """)
                .param("accountId", accountId)
                .query(String.class)
                .single()).isEqualTo("CONNECTED");

        MvcResult expiredAuthorizeResult = mockMvc.perform(
                        post("/api/marketplace-connections/authorize")
                                .cookie(csrf.cookie(), sessionCookie)
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "marketplace": "TIKTOK_SHOP",
                                          "returnUrl": "http://localhost:5173/connect"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andReturn();
        String expiredAuthorizationUrl = JsonPath.read(
                expiredAuthorizeResult.getResponse().getContentAsString(),
                "$.authorizationUrl");
        String expiredState = queryParameter(expiredAuthorizationUrl, "state");
        jdbcClient.sql("""
                UPDATE oauth_authorization_sessions
                SET expires_at = TIMESTAMP '2000-01-01 00:00:00'
                WHERE status = 'PENDING'
                """).update();

        mockMvc.perform(get("/api/marketplace-connections/callback/tiktok-shop")
                        .cookie(sessionCookie)
                        .queryParam("code", "expired-code")
                        .queryParam("state", expiredState))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("Location"))
                        .contains("connection=error")
                        .contains("error=OAUTH_STATE_EXPIRED"));
        assertThat(jdbcClient.sql("""
                        SELECT status
                        FROM oauth_authorization_sessions
                        WHERE state_hash = (
                          SELECT state_hash
                          FROM oauth_authorization_sessions
                          WHERE status = 'EXPIRED'
                          LIMIT 1
                        )
                        """)
                .query(String.class)
                .single()).isEqualTo("EXPIRED");

        MvcResult deniedAuthorizeResult = mockMvc.perform(
                        post("/api/marketplace-connections/authorize")
                                .cookie(csrf.cookie(), sessionCookie)
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "marketplace": "TIKTOK_SHOP",
                                          "returnUrl": "http://localhost:5173/connect"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andReturn();
        String deniedAuthorizationUrl = JsonPath.read(
                deniedAuthorizeResult.getResponse().getContentAsString(),
                "$.authorizationUrl");
        String deniedState = queryParameter(deniedAuthorizationUrl, "state");

        mockMvc.perform(get("/api/marketplace-connections/callback/tiktok-shop")
                        .cookie(sessionCookie)
                        .queryParam("error", "access_denied")
                        .queryParam("state", deniedState))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("Location"))
                        .contains("connection=error")
                        .contains("error=OAUTH_ACCESS_DENIED"));
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM oauth_authorization_sessions
                        WHERE status = 'DENIED'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void marketplaceShopCannotBeConnectedByAnotherTenant() throws Exception {
        seedMarketplacePermissions();
        jdbcClient.sql("""
                INSERT INTO marketplaces
                  (id, marketplace_code, marketplace_name, mock_base_url, is_active)
                VALUES
                  ('50000000-0000-0000-0000-000000000001',
                   'TIKTOK_SHOP', 'TikTok Shop', 'http://127.0.0.1', TRUE)
                """).update();
        jdbcClient.sql("""
                INSERT INTO tenants (id, tenant_code, tenant_name, status)
                VALUES
                  ('10000000-0000-0000-0000-000000000002',
                   'OTHER_SHOP', 'Doanh nghiệp khác', 'ACTIVE')
                """).update();
        jdbcClient.sql("""
                INSERT INTO marketplace_accounts
                  (id, tenant_id, marketplace_id, external_account_id,
                   external_shop_name, site_id, currency, timezone_name,
                   connection_status, settings_json, created_at, updated_at)
                VALUES
                  ('60000000-0000-0000-0000-000000000001',
                   '10000000-0000-0000-0000-000000000002',
                   '50000000-0000-0000-0000-000000000001',
                   'tts-integration-shop', 'TikTok Integration Shop',
                   'VN', 'VND', 'Asia/Ho_Chi_Minh',
                   'CONNECTED', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).update();

        Csrf csrf = getCsrf();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@demo.vn",
                                  "password": "Tenant@123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie =
                loginResult.getResponse().getCookie("omni_tenant_session");
        mockMvc.perform(post("/api/auth/change-password")
                        .cookie(csrf.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Tenant@123",
                                  "newPassword": "NewTenant@123",
                                  "confirmPassword": "NewTenant@123"
                                }
                                """))
                .andExpect(status().isOk());

        MvcResult authorizeResult = mockMvc.perform(
                        post("/api/marketplace-connections/authorize")
                                .cookie(csrf.cookie(), sessionCookie)
                                .header("X-XSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "marketplace": "TIKTOK_SHOP",
                                          "returnUrl": "http://localhost:5173/connect"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andReturn();
        String authorizationUrl = JsonPath.read(
                authorizeResult.getResponse().getContentAsString(),
                "$.authorizationUrl");
        String state = queryParameter(authorizationUrl, "state");

        mockMvc.perform(get("/api/marketplace-connections/callback/tiktok-shop")
                        .cookie(sessionCookie)
                        .queryParam("code", "integration-authorization-code")
                        .queryParam("state", state))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("Location"))
                        .contains("connection=error")
                        .contains("error=SHOP_ALREADY_CONNECTED_TO_ANOTHER_TENANT"));

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM marketplace_accounts
                        WHERE marketplace_id =
                              '50000000-0000-0000-0000-000000000001'
                          AND external_account_id = 'tts-integration-shop'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT tenant_id
                        FROM marketplace_accounts
                        WHERE marketplace_id =
                              '50000000-0000-0000-0000-000000000001'
                          AND external_account_id = 'tts-integration-shop'
                        """)
                .query(String.class)
                .single()).isEqualTo("10000000-0000-0000-0000-000000000002");
        assertThat(jdbcClient.sql("""
                        SELECT status
                        FROM oauth_authorization_sessions
                        WHERE state_hash IS NOT NULL
                        """)
                .query(String.class)
                .single()).isEqualTo("FAILED");

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO marketplace_accounts
                          (id, tenant_id, marketplace_id, external_account_id,
                           external_shop_name, site_id, currency, timezone_name,
                           connection_status, settings_json, created_at, updated_at)
                        VALUES
                          ('60000000-0000-0000-0000-000000000002',
                           :tenantId,
                           '50000000-0000-0000-0000-000000000001',
                           'tts-integration-shop', 'Duplicate Shop',
                           'VN', 'VND', 'Asia/Ho_Chi_Minh',
                           'CONNECTED', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .param("tenantId", TENANT_ID)
                .update()).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void seedMarketplacePermissions() {
        jdbcClient.sql("""
                INSERT INTO permissions (id, permission_code)
                VALUES
                  ('40000000-0000-0000-0000-000000000002', 'ACCOUNT.CONNECT'),
                  ('40000000-0000-0000-0000-000000000003', 'ACCOUNT.DISCONNECT')
                """).update();
        jdbcClient.sql("""
                INSERT INTO role_permissions (role_id, permission_id)
                VALUES
                  (:roleId, '40000000-0000-0000-0000-000000000002'),
                  (:roleId, '40000000-0000-0000-0000-000000000003')
                """)
                .param("roleId", ROLE_ID)
                .update();
    }

    private Csrf getCsrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return new Csrf(cookie.getValue(), cookie);
    }

    private record Csrf(String token, Cookie cookie) {
    }

    private static HttpServer startMarketplaceServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mock/oauth/token", exchange -> respondJson(
                    exchange,
                    """
                    {"data":{
                      "access_token":"integration-access-token",
                      "refresh_token":"integration-refresh-token",
                      "expires_in":3600,
                      "refresh_expires_in":2592000,
                      "scopes":["product.read","product.write","order.read","chat.read"]
                    }}
                    """));
            server.createContext("/mock/oauth/shop", exchange -> respondJson(
                    exchange,
                    """
                    {"data":{
                      "external_account_id":"tts-integration-shop",
                      "shop_cipher":"tts-integration-cipher",
                      "shop_name":"TikTok Integration Shop",
                      "site_id":"VN",
                      "currency":"VND",
                      "timezone_name":"Asia/Ho_Chi_Minh"
                    }}
                    """));
            server.createContext("/mock/oauth/revoke", exchange -> respondJson(
                    exchange,
                    "{\"data\":{\"revoked\":true}}"));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start marketplace test server", exception);
        }
    }

    private static void respondJson(HttpExchange exchange, String payload)
            throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String queryParameter(String url, String name) {
        return URI.create(url).getRawQuery().lines()
                .flatMap(query -> java.util.Arrays.stream(query.split("&")))
                .map(value -> value.split("=", 2))
                .filter(parts -> parts.length == 2 && parts[0].equals(name))
                .map(parts -> java.net.URLDecoder.decode(
                        parts[1],
                        StandardCharsets.UTF_8))
                .findFirst()
                .orElse(null);
    }
}
