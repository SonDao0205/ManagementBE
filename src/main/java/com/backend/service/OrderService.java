package com.backend.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.dto.OrderResponse;
import com.backend.dto.OrderResponse.OrderItemResponse;
import com.backend.dto.OrderSyncResponse;
import com.backend.entity.MarketplaceAccountEntity;
import com.backend.entity.MarketplaceCredentialEntity;
import com.backend.entity.MarketplaceEntity;
import com.backend.marketplace.MarketplaceConnector;
import com.backend.marketplace.MarketplaceConnector.OrderItemPayload;
import com.backend.marketplace.MarketplaceConnector.OrderPayload;
import com.backend.marketplace.MarketplaceConnectorRegistry;
import com.backend.repository.MarketplaceAccountRepository;
import com.backend.repository.MarketplaceCredentialRepository;
import com.backend.repository.MarketplaceRepository;
import com.backend.security.CredentialEncryptionService;
import com.backend.security.TenantPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional
public class OrderService {

    private static final Set<String> UI_STATUSES =
            Set.of("PENDING", "PACKED", "IN_TRANSIT", "DELIVERED", "CANCELLED", "RETURNED");

    private final JdbcTemplate jdbcTemplate;
    private final MarketplaceAccountRepository accountRepository;
    private final MarketplaceCredentialRepository credentialRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final MarketplaceConnectorRegistry connectorRegistry;
    private final MarketplaceConnectionService connectionService;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrderService(
            JdbcTemplate jdbcTemplate,
            MarketplaceAccountRepository accountRepository,
            MarketplaceCredentialRepository credentialRepository,
            MarketplaceRepository marketplaceRepository,
            MarketplaceConnectorRegistry connectorRegistry,
            MarketplaceConnectionService connectionService,
            CredentialEncryptionService encryptionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.encryptionService = encryptionService;
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> list(
            String tenantId,
            String search,
            String status,
            Pageable pageable) {
        StringBuilder where = new StringBuilder(
                " WHERE o.tenant_id = ? AND o.deleted_at IS NULL ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        if (search != null && !search.isBlank()) {
            where.append("""
                     AND (
                       LOWER(COALESCE(o.order_code, '')) LIKE ?
                       OR LOWER(o.external_order_id) LIKE ?
                       OR LOWER(COALESCE(o.customer_name, '')) LIKE ?
                       OR COALESCE(o.customer_phone, '') LIKE ?
                     )
                    """);
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
        }
        String normalizedStatus = normalizeUiStatus(status, false);
        if (normalizedStatus != null) {
            where.append(" AND o.status = ?");
            parameters.add(normalizedStatus);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders o" + where,
                Long.class,
                parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(pageable.getPageSize());
        pageParameters.add(pageable.getOffset());
        List<OrderResponse> orders = jdbcTemplate.query(
                """
                SELECT
                  o.id, o.tenant_id, COALESCE(o.order_code, o.external_order_id) AS order_code,
                  o.external_order_id, o.marketplace, COALESCE(o.customer_name, 'Khách hàng') customer_name,
                  COALESCE(o.customer_phone, '') customer_phone,
                  CAST(o.shipping_address_json AS CHAR) shipping_address_json,
                  o.total_amount, o.shipping_amount, o.discount_amount,
                  CASE WHEN o.final_amount > 0 THEN o.final_amount ELSE o.total_amount END final_amount,
                  o.payment_status, o.status,
                  COALESCE(s.tracking_number, s.waybill_code) tracking_number,
                  o.created_at, o.updated_at
                FROM orders o
                LEFT JOIN shipments s
                  ON s.order_id = o.id AND s.tenant_id = o.tenant_id
                """ + where + """

                ORDER BY o.updated_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapOrder,
                pageParameters.toArray());
        return new PageImpl<>(orders, pageable, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String tenantId, String orderId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                      o.id, o.tenant_id, COALESCE(o.order_code, o.external_order_id) AS order_code,
                      o.external_order_id, o.marketplace, COALESCE(o.customer_name, 'Khách hàng') customer_name,
                      COALESCE(o.customer_phone, '') customer_phone,
                      CAST(o.shipping_address_json AS CHAR) shipping_address_json,
                      o.total_amount, o.shipping_amount, o.discount_amount,
                      CASE WHEN o.final_amount > 0 THEN o.final_amount ELSE o.total_amount END final_amount,
                      o.payment_status, o.status,
                      COALESCE(s.tracking_number, s.waybill_code) tracking_number,
                      o.created_at, o.updated_at
                    FROM orders o
                    LEFT JOIN shipments s
                      ON s.order_id = o.id AND s.tenant_id = o.tenant_id
                    WHERE o.id = ? AND o.tenant_id = ? AND o.deleted_at IS NULL
                    LIMIT 1
                    """,
                    this::mapOrder,
                    orderId,
                    tenantId);
        } catch (EmptyResultDataAccessException exception) {
            throw problem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng.");
        }
    }

    public OrderSyncResponse sync(TenantPrincipal principal) {
        List<MarketplaceAccountEntity> accounts = connectedAccounts(principal.tenantId());
        if (accounts.isEmpty()) {
            throw problem(
                    HttpStatus.BAD_REQUEST,
                    "NO_CONNECTED_MARKETPLACE",
                    "Chưa có TikTok Shop hoặc Lazada nào được kết nối.");
        }
        int synchronizedOrders = 0;
        List<String> errors = new ArrayList<>();
        for (MarketplaceAccountEntity account : accounts) {
            MarketplaceEntity marketplace = requireMarketplace(account.getMarketplaceId());
            try {
                MarketplaceConnector connector = connectorRegistry.require(
                        marketplace.getMarketplaceCode());
                String accessToken = activeAccessToken(principal, account);
                for (OrderPayload order : connector.getOrders(accessToken)) {
                    saveOrder(
                            principal.tenantId(),
                            account,
                            marketplace,
                            order,
                            "POLLING",
                            null);
                    synchronizedOrders++;
                }
            } catch (RuntimeException exception) {
                errors.add(marketplace.getMarketplaceName() + ": " + safeMessage(exception));
            }
        }
        if (errors.size() == accounts.size()) {
            throw problem(
                    HttpStatus.BAD_GATEWAY,
                    "ORDER_SYNC_FAILED",
                    String.join("; ", errors));
        }
        return new OrderSyncResponse(
                accounts.size(),
                synchronizedOrders,
                errors.size(),
                errors);
    }

    public OrderResponse updateStatus(
            TenantPrincipal principal,
            String orderId,
            String requestedStatus) {
        String uiStatus = normalizeUiStatus(requestedStatus, true);
        LocalOrder localOrder = requireLocalOrder(principal.tenantId(), orderId);
        MarketplaceAccountEntity account = accountRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(
                        localOrder.marketplaceAccountId(),
                        principal.tenantId())
                .orElseThrow(() -> problem(
                        HttpStatus.BAD_REQUEST,
                        "MARKETPLACE_ACCOUNT_NOT_FOUND",
                        "Không tìm thấy kết nối sàn của đơn hàng."));
        MarketplaceEntity marketplace = requireMarketplace(account.getMarketplaceId());
        MarketplaceConnector connector = connectorRegistry.require(
                marketplace.getMarketplaceCode());
        OrderPayload updated = connector.updateOrderStatus(
                activeAccessToken(principal, account),
                localOrder.externalOrderId(),
                canonicalStatus(uiStatus));
        saveOrder(
                principal.tenantId(),
                account,
                marketplace,
                updated,
                "USER_ACTION",
                principal.userId());
        return get(principal.tenantId(), orderId);
    }

    private void saveOrder(
            String tenantId,
            MarketplaceAccountEntity account,
            MarketplaceEntity marketplace,
            OrderPayload order,
            String historySource,
            String changedByUserId) {
        ExistingOrder existing = findExistingOrder(account.getId(), order.externalOrderId());
        String orderId = existing == null ? UUID.randomUUID().toString() : existing.id();
        String uiStatus = uiStatus(order.canonicalStatus());
        Instant now = Instant.now();
        String marketplaceLabel = marketplaceLabel(marketplace.getMarketplaceCode());
        String addressJson = json(order.shippingAddress());
        if (existing == null) {
            jdbcTemplate.update(
                    """
                    INSERT INTO orders (
                      id, tenant_id, marketplace_account_id, external_order_id,
                      raw_status, canonical_status, payment_status, refund_status,
                      currency, subtotal_amount, shipping_amount, discount_amount,
                      tax_amount, total_amount, shipping_address_json,
                      billing_address_json, raw_payload, external_created_at,
                      external_updated_at, last_synced_at, version, created_at,
                      updated_at, order_code, customer_name, customer_phone,
                      final_amount, marketplace, status
                    ) VALUES (
                      ?, ?, ?, ?, ?, ?, ?, 'NONE', ?, ?, ?, ?, 0, ?, ?,
                      JSON_OBJECT(), JSON_OBJECT(), ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """,
                    orderId,
                    tenantId,
                    account.getId(),
                    order.externalOrderId(),
                    order.rawStatus(),
                    order.canonicalStatus(),
                    order.paymentStatus(),
                    order.currency(),
                    order.subtotalAmount(),
                    order.shippingAmount(),
                    order.discountAmount(),
                    order.totalAmount(),
                    addressJson,
                    Timestamp.from(order.createdAt()),
                    Timestamp.from(order.updatedAt()),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    order.externalOrderId(),
                    order.customerName(),
                    order.customerPhone(),
                    order.totalAmount(),
                    marketplaceLabel,
                    uiStatus);
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE orders SET
                      raw_status = ?, canonical_status = ?, payment_status = ?,
                      currency = ?, subtotal_amount = ?, shipping_amount = ?,
                      discount_amount = ?, total_amount = ?, final_amount = ?,
                      shipping_address_json = ?, customer_name = ?, customer_phone = ?,
                      marketplace = ?, status = ?, external_updated_at = ?,
                      last_synced_at = ?, updated_at = ?, version = version + 1
                    WHERE id = ? AND tenant_id = ?
                    """,
                    order.rawStatus(),
                    order.canonicalStatus(),
                    order.paymentStatus(),
                    order.currency(),
                    order.subtotalAmount(),
                    order.shippingAmount(),
                    order.discountAmount(),
                    order.totalAmount(),
                    order.totalAmount(),
                    addressJson,
                    order.customerName(),
                    order.customerPhone(),
                    marketplaceLabel,
                    uiStatus,
                    Timestamp.from(order.updatedAt()),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    orderId,
                    tenantId);
        }
        saveItems(tenantId, orderId, order, now);
        saveShipment(tenantId, orderId, order, now);
        if (existing == null || !existing.canonicalStatus().equals(order.canonicalStatus())) {
            jdbcTemplate.update(
                    """
                    INSERT INTO order_status_history (
                      id, tenant_id, order_id, from_raw_status, to_raw_status,
                      from_canonical_status, to_canonical_status, source,
                      external_event_id, changed_by_user_id, occurred_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    tenantId,
                    orderId,
                    existing == null ? null : existing.rawStatus(),
                    order.rawStatus(),
                    existing == null ? null : existing.canonicalStatus(),
                    order.canonicalStatus(),
                    historySource,
                    UUID.randomUUID().toString(),
                    changedByUserId,
                    Timestamp.from(order.updatedAt()),
                    Timestamp.from(now));
        }
    }

    private void saveItems(
            String tenantId,
            String orderId,
            OrderPayload order,
            Instant now) {
        for (OrderItemPayload item : order.items()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO order_items (
                      id, tenant_id, order_id, external_order_item_id,
                      external_product_id, external_sku_id, seller_sku_snapshot,
                      product_name_snapshot, variant_name_snapshot, quantity,
                      unit_price, discount_amount, paid_amount, currency,
                      raw_status, canonical_status, raw_payload, created_at,
                      updated_at, product_name, sku, variant_name, price
                    ) VALUES (
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      JSON_OBJECT(), ?, ?, ?, ?, ?, ?
                    )
                    ON DUPLICATE KEY UPDATE
                      seller_sku_snapshot = VALUES(seller_sku_snapshot),
                      product_name_snapshot = VALUES(product_name_snapshot),
                      variant_name_snapshot = VALUES(variant_name_snapshot),
                      quantity = VALUES(quantity), unit_price = VALUES(unit_price),
                      discount_amount = VALUES(discount_amount),
                      paid_amount = VALUES(paid_amount), raw_status = VALUES(raw_status),
                      canonical_status = VALUES(canonical_status),
                      product_name = VALUES(product_name), sku = VALUES(sku),
                      variant_name = VALUES(variant_name), price = VALUES(price),
                      updated_at = VALUES(updated_at)
                    """,
                    UUID.randomUUID().toString(),
                    tenantId,
                    orderId,
                    item.externalOrderItemId(),
                    emptyToFallback(item.externalProductId(), item.externalOrderItemId()),
                    item.externalSkuId(),
                    item.sellerSku(),
                    item.productName(),
                    item.variantName(),
                    item.quantity(),
                    item.unitPrice(),
                    item.discountAmount(),
                    item.paidAmount(),
                    order.currency(),
                    order.rawStatus(),
                    order.canonicalStatus(),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    item.productName(),
                    item.sellerSku(),
                    item.variantName(),
                    item.unitPrice());
        }
    }

    private void saveShipment(
            String tenantId,
            String orderId,
            OrderPayload order,
            Instant now) {
        String shipmentStatus = shipmentStatus(order.canonicalStatus());
        if (shipmentStatus == null) {
            return;
        }
        String packageId = emptyToFallback(
                order.externalPackageId(),
                "PKG-" + order.externalOrderId());
        String trackingNumber = emptyToFallback(
                order.trackingNumber(),
                packageId);
        String provider = emptyToFallback(
                order.shippingProvider(),
                "Marketplace Logistics");
        String milestoneType = milestoneType(shipmentStatus);
        String milestone = milestoneLabel(shipmentStatus);
        String destination = String.valueOf(
                order.shippingAddress().getOrDefault("fullAddress", order.customerName()));
        Timestamp readyAt = "READY_TO_SHIP".equals(shipmentStatus)
                ? Timestamp.from(order.updatedAt())
                : null;
        Timestamp shippedAt = Set.of("SHIPPED", "IN_TRANSIT", "DELIVERED")
                .contains(shipmentStatus)
                ? Timestamp.from(order.updatedAt())
                : null;
        Timestamp deliveredAt = "DELIVERED".equals(shipmentStatus)
                ? Timestamp.from(order.updatedAt())
                : null;
        jdbcTemplate.update(
                """
                INSERT INTO shipments (
                  id, tenant_id, order_id, external_package_id, tracking_number,
                  shipping_provider, raw_status, canonical_status,
                  package_items_json, ready_to_ship_at, shipped_at, delivered_at,
                  raw_payload, created_at, updated_at, waybill_code, carrier_name,
                  destination, cod_amount, latest_milestone, milestone_type
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?, JSON_ARRAY(), ?, ?, ?,
                  JSON_OBJECT(), ?, ?, ?, ?, ?, ?, ?, ?
                )
                ON DUPLICATE KEY UPDATE
                  tracking_number = VALUES(tracking_number),
                  shipping_provider = VALUES(shipping_provider),
                  raw_status = VALUES(raw_status),
                  canonical_status = VALUES(canonical_status),
                  ready_to_ship_at = COALESCE(ready_to_ship_at, VALUES(ready_to_ship_at)),
                  shipped_at = COALESCE(shipped_at, VALUES(shipped_at)),
                  delivered_at = COALESCE(delivered_at, VALUES(delivered_at)),
                  waybill_code = VALUES(waybill_code),
                  carrier_name = VALUES(carrier_name),
                  destination = VALUES(destination),
                  latest_milestone = VALUES(latest_milestone),
                  milestone_type = VALUES(milestone_type),
                  updated_at = VALUES(updated_at)
                """,
                UUID.randomUUID().toString(),
                tenantId,
                orderId,
                packageId,
                trackingNumber,
                provider,
                order.rawStatus(),
                shipmentStatus,
                readyAt,
                shippedAt,
                deliveredAt,
                Timestamp.from(now),
                Timestamp.from(now),
                trackingNumber,
                provider,
                destination,
                "PAID".equals(order.paymentStatus()) ? BigDecimal.ZERO : order.totalAmount(),
                milestone,
                milestoneType);
    }

    private OrderResponse mapOrder(ResultSet resultSet, int rowNumber) throws SQLException {
        String id = resultSet.getString("id");
        String tenantId = resultSet.getString("tenant_id");
        List<OrderItemResponse> items = jdbcTemplate.query(
                """
                SELECT id, COALESCE(product_name, product_name_snapshot) product_name,
                       COALESCE(sku, seller_sku_snapshot, '') sku,
                       COALESCE(variant_name, variant_name_snapshot, '') variant_name,
                       CASE WHEN price > 0 THEN price ELSE unit_price END price,
                       quantity
                FROM order_items
                WHERE order_id = ? AND tenant_id = ?
                ORDER BY created_at, id
                """,
                (itemRows, itemRowNumber) -> new OrderItemResponse(
                        itemRows.getString("id"),
                        itemRows.getString("product_name"),
                        itemRows.getString("sku"),
                        itemRows.getString("variant_name"),
                        itemRows.getBigDecimal("price"),
                        itemRows.getInt("quantity")),
                id,
                tenantId);
        return new OrderResponse(
                id,
                tenantId,
                resultSet.getString("order_code"),
                resultSet.getString("external_order_id"),
                resultSet.getString("marketplace"),
                resultSet.getString("customer_name"),
                resultSet.getString("customer_phone"),
                resultSet.getString("shipping_address_json"),
                resultSet.getBigDecimal("total_amount"),
                resultSet.getBigDecimal("shipping_amount"),
                resultSet.getBigDecimal("discount_amount"),
                resultSet.getBigDecimal("final_amount"),
                resultSet.getString("payment_status"),
                resultSet.getString("status"),
                resultSet.getString("tracking_number"),
                items,
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private ExistingOrder findExistingOrder(String accountId, String externalOrderId) {
        List<ExistingOrder> orders = jdbcTemplate.query(
                """
                SELECT id, raw_status, canonical_status
                FROM orders
                WHERE marketplace_account_id = ? AND external_order_id = ?
                LIMIT 1
                """,
                (rows, rowNumber) -> new ExistingOrder(
                        rows.getString("id"),
                        rows.getString("raw_status"),
                        rows.getString("canonical_status")),
                accountId,
                externalOrderId);
        return orders.isEmpty() ? null : orders.get(0);
    }

    private LocalOrder requireLocalOrder(String tenantId, String orderId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT marketplace_account_id, external_order_id
                    FROM orders
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                    """,
                    (rows, rowNumber) -> new LocalOrder(
                            rows.getString("marketplace_account_id"),
                            rows.getString("external_order_id")),
                    orderId,
                    tenantId);
        } catch (EmptyResultDataAccessException exception) {
            throw problem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng.");
        }
    }

    private List<MarketplaceAccountEntity> connectedAccounts(String tenantId) {
        return accountRepository
                .findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                .stream()
                .filter(account -> "CONNECTED".equals(account.getConnectionStatus()))
                .toList();
    }

    private String activeAccessToken(
            TenantPrincipal principal,
            MarketplaceAccountEntity account) {
        MarketplaceCredentialEntity credential = requireCredential(account.getId());
        if (credential.getAccessTokenExpiresAt() != null
                && credential.getAccessTokenExpiresAt().isBefore(Instant.now().plusSeconds(30))) {
            connectionService.refresh(principal, account.getId());
            credential = requireCredential(account.getId());
        }
        return encryptionService.decrypt(credential.getAccessTokenEncrypted());
    }

    private MarketplaceCredentialEntity requireCredential(String accountId) {
        return credentialRepository.findByMarketplaceAccountId(accountId)
                .orElseThrow(() -> problem(
                        HttpStatus.BAD_REQUEST,
                        "MARKETPLACE_CREDENTIAL_NOT_FOUND",
                        "Shop chưa có thông tin xác thực hợp lệ."));
    }

    private MarketplaceEntity requireMarketplace(String marketplaceId) {
        return marketplaceRepository.findById(marketplaceId)
                .orElseThrow(() -> problem(
                        HttpStatus.BAD_REQUEST,
                        "MARKETPLACE_NOT_FOUND",
                        "Không tìm thấy cấu hình sàn."));
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize order address", exception);
        }
    }

    private static String normalizeUiStatus(String status, boolean required) {
        if (status == null || status.isBlank()) {
            if (required) {
                throw problem(HttpStatus.BAD_REQUEST, "INVALID_ORDER_STATUS", "Thiếu trạng thái đơn hàng.");
            }
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!UI_STATUSES.contains(normalized)) {
            if (required) {
                throw problem(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ORDER_STATUS",
                        "Trạng thái đơn hàng không hợp lệ.");
            }
            return null;
        }
        return normalized;
    }

    private static String canonicalStatus(String uiStatus) {
        return switch (uiStatus) {
            case "PENDING" -> "CONFIRMED";
            case "PACKED" -> "READY_TO_SHIP";
            case "IN_TRANSIT" -> "IN_TRANSIT";
            case "DELIVERED" -> "DELIVERED";
            case "CANCELLED" -> "CANCELLED";
            case "RETURNED" -> "RETURNED";
            default -> throw new IllegalArgumentException("Unsupported order status");
        };
    }

    private static String uiStatus(String canonicalStatus) {
        return switch (canonicalStatus) {
            case "CREATED", "CONFIRMED" -> "PENDING";
            case "READY_TO_SHIP" -> "PACKED";
            case "SHIPPED", "IN_TRANSIT" -> "IN_TRANSIT";
            case "DELIVERED" -> "DELIVERED";
            case "CANCELLED", "FAILED" -> "CANCELLED";
            case "RETURN_REQUESTED", "RETURNED" -> "RETURNED";
            default -> "PENDING";
        };
    }

    private static String shipmentStatus(String canonicalStatus) {
        return switch (canonicalStatus) {
            case "READY_TO_SHIP" -> "READY_TO_SHIP";
            case "SHIPPED" -> "SHIPPED";
            case "IN_TRANSIT" -> "IN_TRANSIT";
            case "DELIVERED" -> "DELIVERED";
            case "CANCELLED" -> "CANCELLED";
            case "RETURNED" -> "RETURNED";
            case "FAILED" -> "FAILED";
            default -> null;
        };
    }

    private static String milestoneType(String shipmentStatus) {
        return switch (shipmentStatus) {
            case "READY_TO_SHIP" -> "picked";
            case "SHIPPED", "IN_TRANSIT" -> "transit";
            case "DELIVERED" -> "success";
            case "CANCELLED", "RETURNED", "FAILED" -> "failed";
            default -> "waiting";
        };
    }

    private static String milestoneLabel(String shipmentStatus) {
        return switch (shipmentStatus) {
            case "READY_TO_SHIP" -> "Đã đóng gói, chờ bàn giao";
            case "SHIPPED", "IN_TRANSIT" -> "Đang vận chuyển";
            case "DELIVERED" -> "Giao hàng thành công";
            case "CANCELLED" -> "Đơn vận chuyển đã hủy";
            case "RETURNED" -> "Đã hoàn hàng";
            case "FAILED" -> "Giao hàng thất bại";
            default -> "Chờ lấy hàng";
        };
    }

    private static String marketplaceLabel(String code) {
        return switch (code) {
            case "TIKTOK_SHOP" -> "TikTok Shop";
            case "LAZADA" -> "Lazada";
            default -> code;
        };
    }

    private static String emptyToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private static AuthenticationException problem(
            HttpStatus status,
            String code,
            String message) {
        return new AuthenticationException(status, code, message);
    }

    private record ExistingOrder(String id, String rawStatus, String canonicalStatus) {
    }

    private record LocalOrder(String marketplaceAccountId, String externalOrderId) {
    }
}
