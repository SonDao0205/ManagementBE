package com.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class TenantAuthenticationIntegrationTest {

    private static final String TENANT_ID = "10000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "20000000-0000-0000-0000-000000000001";
    private static final String ROLE_ID = "30000000-0000-0000-0000-000000000001";
    private static final String PERMISSION_ID = "40000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedTenantLogin() {
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

    private Csrf getCsrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return new Csrf(token, cookie);
    }

    private record Csrf(String token, Cookie cookie) {
    }
}
