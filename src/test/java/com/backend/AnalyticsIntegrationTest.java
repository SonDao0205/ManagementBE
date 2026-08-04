package com.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.security.TenantPrincipal;

@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsIntegrationTest {

    private static final String TENANT_ID = "81000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "82000000-0000-0000-0000-000000000001";
    private static final String TIKTOK_ID = "83000000-0000-0000-0000-000000000001";
    private static final String LAZADA_ID = "83000000-0000-0000-0000-000000000002";
    private static final String TIKTOK_ACCOUNT_ID = "84000000-0000-0000-0000-000000000001";
    private static final String LAZADA_ACCOUNT_ID = "84000000-0000-0000-0000-000000000002";
    private static final String CUSTOMER_ID = "85000000-0000-0000-0000-000000000001";
    private static final String PRODUCT_ID = "86000000-0000-0000-0000-000000000001";

    @DynamicPropertySource
    static void analyticsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:omnichannel_analytics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("app.analytics.weekly-email-enabled", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void seed() {
        cleanup();
        jdbcClient.sql("""
                INSERT INTO tenants (id, tenant_code, tenant_name, contact_email, status)
                VALUES (:id, 'ANALYTICS_SHOP', 'Analytics Shop', 'owner@example.test', 'ACTIVE')
                """).param("id", TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO tenant_users (id, tenant_id, email, display_name, status)
                VALUES (:id, :tenantId, 'analytics@example.test', 'Analytics Owner', 'ACTIVE')
                """).param("id", USER_ID).param("tenantId", TENANT_ID).update();

        insertMarketplace(TIKTOK_ID, "TIKTOK_SHOP", "TikTok Shop");
        insertMarketplace(LAZADA_ID, "LAZADA", "Lazada");
        insertAccount(TIKTOK_ACCOUNT_ID, TIKTOK_ID, "tiktok-shop");
        insertAccount(LAZADA_ACCOUNT_ID, LAZADA_ID, "lazada-shop");

        jdbcClient.sql("""
                INSERT INTO marketplace_customers (
                  id, tenant_id, marketplace_account_id, external_customer_id, display_name
                ) VALUES (:id, :tenantId, :accountId, 'buyer-1', 'Nguyễn An')
                """)
                .param("id", CUSTOMER_ID)
                .param("tenantId", TENANT_ID)
                .param("accountId", TIKTOK_ACCOUNT_ID)
                .update();
        jdbcClient.sql("""
                INSERT INTO products (
                  id, tenant_id, product_code, product_name, status, attributes_json,
                  created_at, updated_at
                ) VALUES (
                  :id, :tenantId, 'P-001', 'Sản phẩm kiểm thử', 'ACTIVE',
                  '{"costPrice":100}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("id", PRODUCT_ID)
                .param("tenantId", TENANT_ID)
                .update();
        jdbcClient.sql("""
                INSERT INTO marketplace_products (
                  id, tenant_id, product_id, marketplace_account_id, external_product_id,
                  raw_status, canonical_status, raw_payload, created_at, updated_at
                ) VALUES (
                  '87000000-0000-0000-0000-000000000001', :tenantId, :productId, :accountId,
                  'tiktok-product-1', 'ACTIVE', 'ACTIVE', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("tenantId", TENANT_ID)
                .param("productId", PRODUCT_ID)
                .param("accountId", TIKTOK_ACCOUNT_ID)
                .update();

        Instant today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .atTime(1, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        Instant yesterday = today.minusSeconds(24 * 60 * 60);
        insertOrder("88000000-0000-0000-0000-000000000001", "ORD-CONFIRMED",
                "CONFIRMED", new BigDecimal("1000"), new BigDecimal("50"), today, 2);
        insertOrder("88000000-0000-0000-0000-000000000002", "ORD-READY",
                "READY_TO_SHIP", new BigDecimal("2000"), new BigDecimal("100"), today.plusSeconds(60), 1);
        insertOrder("88000000-0000-0000-0000-000000000003", "ORD-NEW",
                "CREATED", new BigDecimal("500"), BigDecimal.ZERO, today.plusSeconds(120), 1);
        insertOrder("88000000-0000-0000-0000-000000000004", "ORD-DELIVERED",
                "DELIVERED", new BigDecimal("3000"), new BigDecimal("200"), yesterday, 3);

        jdbcClient.sql("""
                INSERT INTO conversations (
                  id, tenant_id, marketplace_account_id, marketplace_customer_id, ai_mode
                ) VALUES (
                  '89000000-0000-0000-0000-000000000001', :tenantId, :accountId, :customerId,
                  'SUGGEST_ONLY'
                )
                """)
                .param("tenantId", TENANT_ID)
                .param("accountId", TIKTOK_ACCOUNT_ID)
                .param("customerId", CUSTOMER_ID)
                .update();
        jdbcClient.sql("""
                INSERT INTO messages (
                  id, tenant_id, conversation_id, direction, sender_type, text_content,
                  external_created_at, created_at
                ) VALUES (
                  '8a000000-0000-0000-0000-000000000001', :tenantId,
                  '89000000-0000-0000-0000-000000000001', 'INBOUND', 'CUSTOMER',
                  'Shop còn hàng không?', :createdAt, :createdAt
                )
                """)
                .param("tenantId", TENANT_ID)
                .param("createdAt", Timestamp.from(today))
                .update();
    }

    @AfterEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM weekly_analytics_email_deliveries WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM messages WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM conversations WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM order_items WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM marketplace_products WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM products WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM marketplace_customers WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM marketplace_accounts WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM tenant_users WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM tenants WHERE id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM marketplaces WHERE id IN (:tiktokId, :lazadaId)")
                .param("tiktokId", TIKTOK_ID).param("lazadaId", LAZADA_ID).update();
    }

    @Test
    void overviewUsesTodayOrdersFirstContactsChannelsAndFiveMostRecentOrders() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/overview").with(authentication(reportAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayRevenue").value(3000))
                .andExpect(jsonPath("$.newOrdersToday").value(3))
                .andExpect(jsonPath("$.newCustomersToday").value(1))
                .andExpect(jsonPath("$.channels[0].marketplace").value("TIKTOK_SHOP"))
                .andExpect(jsonPath("$.channels[0].productCount").value(1))
                .andExpect(jsonPath("$.channels[1].marketplace").value("LAZADA"))
                .andExpect(jsonPath("$.channels[1].productCount").value(0))
                .andExpect(jsonPath("$.recentOrders.length()").value(4))
                .andExpect(jsonPath("$.recentOrders[0].externalOrderId").value("ORD-NEW"));
    }

    @Test
    void revenueAnalyticsCalculatesRevenueCostProfitMarginAndGrowth() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/revenue")
                        .queryParam("period", "this-month")
                        .with(authentication(reportAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(6000))
                .andExpect(jsonPath("$.totalCost").value(950))
                .andExpect(jsonPath("$.netProfit").value(5050))
                .andExpect(jsonPath("$.profitMargin").value(84.17))
                .andExpect(jsonPath("$.growthPercent").value(100))
                .andExpect(jsonPath("$.chartGranularity").value("DAY"))
                .andExpect(jsonPath("$.chartPoints.length()").value(greaterThan(0)));
    }

    @Test
    void analyticsRequiresReportReadPermission() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/overview").with(authentication(noReportAuthentication())))
                .andExpect(status().isForbidden());
    }

    @Test
    void chartUsesDaysForMonthMonthsForQuarterAndQuartersForYear() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/revenue")
                        .queryParam("period", "this-month")
                        .with(authentication(reportAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chartGranularity").value("DAY"));

        mockMvc.perform(get("/api/v1/analytics/revenue")
                        .queryParam("period", "this-quarter")
                        .with(authentication(reportAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chartGranularity").value("MONTH"));

        mockMvc.perform(get("/api/v1/analytics/revenue")
                        .queryParam("period", "this-year")
                        .with(authentication(reportAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chartGranularity").value("QUARTER"));
    }

    private void insertMarketplace(String id, String code, String name) {
        jdbcClient.sql("""
                INSERT INTO marketplaces (id, marketplace_code, marketplace_name, is_active)
                VALUES (:id, :code, :name, TRUE)
                """).param("id", id).param("code", code).param("name", name).update();
    }

    private void insertAccount(String id, String marketplaceId, String externalId) {
        jdbcClient.sql("""
                INSERT INTO marketplace_accounts (
                  id, tenant_id, marketplace_id, external_account_id, external_shop_name,
                  site_id, currency, timezone_name, connection_status, settings_json,
                  created_at, updated_at
                ) VALUES (
                  :id, :tenantId, :marketplaceId, :externalId, :externalId,
                  'VN', 'VND', 'Asia/Ho_Chi_Minh', 'CONNECTED', '{}',
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("id", id).param("tenantId", TENANT_ID)
                .param("marketplaceId", marketplaceId).param("externalId", externalId).update();
    }

    private void insertOrder(
            String id,
            String externalId,
            String status,
            BigDecimal total,
            BigDecimal shipping,
            Instant createdAt,
            int quantity) {
        jdbcClient.sql("""
                INSERT INTO orders (
                  id, tenant_id, marketplace_account_id, marketplace_customer_id,
                  external_order_id, raw_status, canonical_status, total_amount, shipping_amount,
                  external_created_at, external_updated_at, last_synced_at, created_at, updated_at
                ) VALUES (
                  :id, :tenantId, :accountId, :customerId, :externalId, :status, :status,
                  :total, :shipping, :createdAt, :createdAt, :createdAt, :createdAt, :createdAt
                )
                """)
                .param("id", id).param("tenantId", TENANT_ID).param("accountId", TIKTOK_ACCOUNT_ID)
                .param("customerId", CUSTOMER_ID).param("externalId", externalId).param("status", status)
                .param("total", total).param("shipping", shipping)
                .param("createdAt", Timestamp.from(createdAt)).update();

        jdbcClient.sql("""
                INSERT INTO order_items (
                  id, tenant_id, order_id, product_id, external_order_item_id, external_product_id,
                  product_name_snapshot, quantity, unit_price, paid_amount, raw_status,
                  canonical_status, created_at, updated_at
                ) VALUES (
                  :id, :tenantId, :orderId, :productId, :itemId, 'tiktok-product-1',
                  'Sản phẩm kiểm thử', :quantity, 100, 100, :status, :status,
                  :createdAt, :createdAt
                )
                """)
                .param("id", id.replace("88", "8b"))
                .param("tenantId", TENANT_ID).param("orderId", id).param("productId", PRODUCT_ID)
                .param("itemId", "ITEM-" + externalId).param("quantity", quantity).param("status", status)
                .param("createdAt", Timestamp.from(createdAt)).update();
    }

    private Authentication reportAuthentication() {
        return authenticationWith(List.of("REPORT.READ"));
    }

    private Authentication noReportAuthentication() {
        return authenticationWith(List.of("ORDER.READ"));
    }

    private Authentication authenticationWith(List<String> permissions) {
        TenantPrincipal principal = new TenantPrincipal(
                "analytics-session", USER_ID, "analytics@example.test", "Analytics Owner", null,
                TENANT_ID, "ANALYTICS_SHOP", "Analytics Shop", List.of("TENANT_MANAGER"),
                permissions, false, Instant.now().plusSeconds(3600));
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("SESSION_AUTHENTICATED"));
        permissions.stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
