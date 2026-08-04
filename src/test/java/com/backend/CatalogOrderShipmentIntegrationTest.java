package com.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;

import com.backend.security.TenantPrincipal;
import com.backend.service.MediaStorageService;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogOrderShipmentIntegrationTest {

    private static final String TENANT_ID = "71000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "72000000-0000-0000-0000-000000000001";
    private static final String ACCOUNT_ID = "73000000-0000-0000-0000-000000000001";
    private static final String ORDER_ID = "74000000-0000-0000-0000-000000000001";

    @DynamicPropertySource
    static void featureDatabase(DynamicPropertyRegistry registry) {
        String postgresUrl = System.getenv("FEATURE_TEST_DB_URL");
        if (postgresUrl == null || postgresUrl.isBlank()) {
            registry.add("spring.datasource.url", () ->
                    "jdbc:h2:mem:omnichannel_features;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return;
        }
        registry.add("spring.datasource.url", () -> postgresUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () ->
                System.getenv().getOrDefault("FEATURE_TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("FEATURE_TEST_DB_PASSWORD", ""));
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () ->
                "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private MediaStorageService mediaStorageService;

    @BeforeEach
    void prepareTenant() {
        when(mediaStorageService.upload(any(byte[].class), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String publicId = invocation.getArgument(3);
                    return new MediaStorageService.StoredMedia(
                            publicId,
                            "https://res.cloudinary.test/" + publicId);
                });
        jdbcClient.sql("DELETE FROM order_status_history WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM shipments WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM order_items WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM product_media WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM product_variants WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM products WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM marketplace_accounts WHERE id = :id")
                .param("id", ACCOUNT_ID).update();
        jdbcClient.sql("DELETE FROM tenant_users WHERE id = :id")
                .param("id", USER_ID).update();
        jdbcClient.sql("DELETE FROM tenants WHERE id = :id")
                .param("id", TENANT_ID).update();

        jdbcClient.sql("""
                INSERT INTO tenants (id, tenant_code, tenant_name, status)
                VALUES (:id, 'FEATURE_SHOP', 'Cửa hàng kiểm thử chức năng', 'ACTIVE')
                """).param("id", TENANT_ID).update();
        jdbcClient.sql("""
                INSERT INTO tenant_users (id, tenant_id, email, display_name, status)
                VALUES (:id, :tenantId, 'feature-owner@example.test', 'Feature Owner', 'ACTIVE')
                """)
                .param("id", USER_ID)
                .param("tenantId", TENANT_ID)
                .update();
    }

    @AfterEach
    void cleanFeatureTenant() {
        jdbcClient.sql("DELETE FROM order_status_history WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM shipments WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM order_items WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM orders WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM product_media WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM product_variants WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM products WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbcClient.sql("DELETE FROM marketplace_accounts WHERE id = :id")
                .param("id", ACCOUNT_ID).update();
        jdbcClient.sql("DELETE FROM tenant_users WHERE id = :id")
                .param("id", USER_ID).update();
        jdbcClient.sql("DELETE FROM tenants WHERE id = :id")
                .param("id", TENANT_ID).update();
    }

    @Test
    void productCrudPersistsCanonicalProductAndVariantData() throws Exception {
        String createBody = """
                {
                  "name":"Áo khoác kiểm thử",
                  "productCode":"AO-TEST-01",
                  "category":"AO_KHOAC",
                  "description":"Sản phẩm nội bộ",
                  "price":250000,
                  "costPrice":150000,
                  "totalStock":8,
                  "minStockAlert":2,
                  "imageUrl":"https://example.test/ao.jpg",
                  "status":"ACTIVE",
                  "variants":[
                    {"sku":"AO-TEST-01-M","variantName":"Size M","price":250000,"stockQuantity":8}
                  ]
                }
                """;

        String productId = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/v1/products")
                                .with(authentication(featureAuthentication()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.productCode").value("AO-TEST-01"))
                        .andExpect(jsonPath("$.totalStock").value(8))
                        .andExpect(jsonPath("$.variants[0].availableStock").value(8))
                        .andReturn().getResponse().getContentAsString(),
                "$.id");

        mockMvc.perform(get("/api/v1/products")
                        .with(authentication(featureAuthentication()))
                        .queryParam("search", "Size M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .with(authentication(featureAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody.replace("250000", "275000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(275000));

        mockMvc.perform(post("/api/v1/products/{id}/adjust-stock", productId)
                        .with(authentication(featureAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_AVAILABLE_STOCK"));

        mockMvc.perform(post("/api/v1/products")
                        .with(authentication(featureAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_ALREADY_EXISTS"));

        mockMvc.perform(delete("/api/v1/products/{id}", productId)
                        .with(authentication(featureAuthentication()))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/products/{id}", productId)
                        .with(authentication(featureAuthentication())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void productMediaSupportsMultipleFilesPrimaryOrderingAndDeletion() throws Exception {
        String productId = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/v1/products")
                                .with(authentication(featureAuthentication()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name":"Sản phẩm media",
                                          "productCode":"MEDIA-TEST-01",
                                          "status":"ACTIVE",
                                          "variants":[
                                            {"sku":"MEDIA-TEST-01-DEFAULT","variantName":"Mặc định","price":100000,"stockQuantity":3}
                                          ]
                                        }
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id");

        MockMultipartFile image = new MockMultipartFile(
                "files", "front.jpg", "image/jpeg", "image-content".getBytes());
        MockMultipartFile video = new MockMultipartFile(
                "files", "demo.mp4", "video/mp4", "video-content".getBytes());
        String uploadResponse = mockMvc.perform(multipart("/api/v1/products/{id}/media", productId)
                        .file(image)
                        .file(video)
                        .with(authentication(featureAuthentication()))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].primary").value(true))
                .andExpect(jsonPath("$[0].mediaType").value("IMAGE"))
                .andExpect(jsonPath("$[1].mediaType").value("VIDEO"))
                .andReturn().getResponse().getContentAsString();

        String firstId = com.jayway.jsonpath.JsonPath.read(uploadResponse, "$[0].id");
        String secondId = com.jayway.jsonpath.JsonPath.read(uploadResponse, "$[1].id");
        String firstStorageKey = com.jayway.jsonpath.JsonPath.read(uploadResponse, "$[0].storageKey");

        mockMvc.perform(put("/api/v1/products/{id}/media/order", productId)
                        .with(authentication(featureAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"mediaId":"%s","sortOrder":0,"primary":true},
                                  {"mediaId":"%s","sortOrder":1,"primary":false}
                                ]}
                                """.formatted(secondId, firstId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(secondId))
                .andExpect(jsonPath("$[0].primary").value(true));

        mockMvc.perform(delete("/api/v1/products/{id}/media/{mediaId}", productId, firstId)
                        .with(authentication(featureAuthentication()))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        verify(mediaStorageService).delete(firstStorageKey, "IMAGE");

        mockMvc.perform(get("/api/v1/products/{id}", productId)
                        .with(authentication(featureAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media.length()").value(1))
                .andExpect(jsonPath("$.media[0].id").value(secondId));
    }

    @Test
    void orderStatusAndShipmentLookupUseCanonicalSchema() throws Exception {
        seedOrderAndShipment();

        mockMvc.perform(get("/api/v1/orders")
                        .with(authentication(featureAuthentication()))
                        .queryParam("search", "Lan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].externalOrderId").value("TTS-ORDER-001"))
                .andExpect(jsonPath("$.content[0].status").value("CREATED"))
                .andExpect(jsonPath("$.content[0].items[0].productName").value("Áo khoác kiểm thử"));

        mockMvc.perform(put("/api/v1/orders/{id}/status", ORDER_ID)
                        .with(authentication(featureAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"note\":\"Đã kiểm tra nội bộ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM order_status_history
                        WHERE order_id = :orderId AND source = 'USER_ACTION'
                        """)
                .param("orderId", ORDER_ID)
                .query(Integer.class).single()).isEqualTo(1);

        mockMvc.perform(put("/api/v1/orders/{id}/status", ORDER_ID)
                        .with(authentication(featureAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS_TRANSITION"));

        mockMvc.perform(get("/api/v1/shipments/track/{code}", "TRACK-001")
                        .with(authentication(featureAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value("TTS-ORDER-001"))
                .andExpect(jsonPath("$.carrierName").value("GHTK"))
                .andExpect(jsonPath("$.milestoneType").value("picked"));

        mockMvc.perform(get("/api/v1/shipments/track/{code}", "TTS-ORDER-001")
                        .with(authentication(featureAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybillCode").value("TRACK-001"));

        mockMvc.perform(get("/api/v1/shipments/overview")
                        .with(authentication(featureAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countPicked").value(1))
                .andExpect(jsonPath("$.countFailed").value(0));

        mockMvc.perform(get("/api/v1/shipments/track/{code}", "KHONG-TON-TAI")
                        .with(authentication(featureAuthentication())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_FOUND"));
    }

    private void seedOrderAndShipment() {
        if (jdbcClient.sql("SELECT COUNT(*) FROM marketplaces WHERE marketplace_code = 'TIKTOK_SHOP'")
                .query(Integer.class).single() == 0) {
            jdbcClient.sql("""
                    INSERT INTO marketplaces (id, marketplace_code, marketplace_name, mock_base_url, is_active)
                    VALUES ('75000000-0000-0000-0000-000000000001', 'TIKTOK_SHOP', 'TikTok Shop', NULL, TRUE)
                    """).update();
        }
        String marketplaceId = jdbcClient.sql(
                        "SELECT id FROM marketplaces WHERE marketplace_code = 'TIKTOK_SHOP'")
                .query(String.class).single();
        jdbcClient.sql("""
                INSERT INTO marketplace_accounts (
                  id, tenant_id, marketplace_id, external_account_id, external_shop_name,
                  site_id, currency, timezone_name, connection_status, settings_json,
                  created_at, updated_at
                ) VALUES (
                  :id, :tenantId, :marketplaceId, 'feature-shop', 'Feature Shop',
                  'VN', 'VND', 'Asia/Ho_Chi_Minh', 'CONNECTED', '{}',
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("id", ACCOUNT_ID)
                .param("tenantId", TENANT_ID)
                .param("marketplaceId", marketplaceId)
                .update();
        jdbcClient.sql("""
                INSERT INTO orders (
                  id, tenant_id, marketplace_account_id, external_order_id,
                  raw_status, canonical_status, payment_status, refund_status, currency,
                  subtotal_amount, shipping_amount, discount_amount, tax_amount, total_amount,
                  shipping_address_json, billing_address_json, raw_payload,
                  external_created_at, external_updated_at, last_synced_at, version,
                  created_at, updated_at
                ) VALUES (
                  :id, :tenantId, :accountId, 'TTS-ORDER-001',
                  'AWAITING_CONFIRMATION', 'CREATED', 'UNPAID', 'NONE', 'VND',
                  250000, 30000, 0, 0, 280000,
                  '{"recipientName":"Nguyễn Lan","phoneNumber":"0901000001","fullAddress":"Cầu Giấy, Hà Nội","city":"Hà Nội"}',
                  '{}', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("id", ORDER_ID)
                .param("tenantId", TENANT_ID)
                .param("accountId", ACCOUNT_ID)
                .update();
        jdbcClient.sql("""
                INSERT INTO order_items (
                  id, tenant_id, order_id, external_order_item_id, external_product_id,
                  seller_sku_snapshot, product_name_snapshot, variant_name_snapshot,
                  quantity, unit_price, discount_amount, paid_amount, currency,
                  raw_status, canonical_status, raw_payload, created_at, updated_at
                ) VALUES (
                  '76000000-0000-0000-0000-000000000001', :tenantId, :orderId,
                  'ITEM-001', 'PRODUCT-001', 'AO-TEST-01-M', 'Áo khoác kiểm thử', 'Size M',
                  1, 250000, 0, 250000, 'VND', 'AWAITING_CONFIRMATION', 'CREATED', '{}',
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("tenantId", TENANT_ID)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("""
                INSERT INTO shipments (
                  id, tenant_id, order_id, external_package_id, tracking_number,
                  shipping_provider, raw_status, canonical_status, package_items_json,
                  shipped_at, raw_payload, created_at, updated_at
                ) VALUES (
                  '77000000-0000-0000-0000-000000000001', :tenantId, :orderId,
                  'PACKAGE-001', 'TRACK-001', 'GHTK', 'PICKED_UP', 'SHIPPED', '[]',
                  CURRENT_TIMESTAMP, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("tenantId", TENANT_ID)
                .param("orderId", ORDER_ID)
                .update();
    }

    private Authentication featureAuthentication() {
        TenantPrincipal principal = new TenantPrincipal(
                "test-session", USER_ID, "feature-owner@example.test", "Feature Owner", null,
                TENANT_ID, "FEATURE_SHOP", "Cửa hàng kiểm thử chức năng",
                List.of("TENANT_MANAGER"), List.of("PRODUCT.READ", "PRODUCT.CREATE", "PRODUCT.UPDATE",
                        "PRODUCT.DELETE", "ORDER.READ", "ORDER.FULFILL"),
                false, Instant.now().plusSeconds(3600));
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("SESSION_AUTHENTICATED")));
    }
}
