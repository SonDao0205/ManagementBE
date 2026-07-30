-- V4: Product, Order & Shipment tables
-- MySQL 8.0+, utf8mb4, multi-tenant, soft delete

-- ─────────────────────────────────────────────────────────────
-- products
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS products (
    id           CHAR(36)       NOT NULL,
    tenant_id    CHAR(36)       NOT NULL,
    name         VARCHAR(255)   NOT NULL,
    product_code VARCHAR(100)   NULL,
    category     VARCHAR(100)   NULL,
    description  TEXT           NULL,
    price        DECIMAL(15,2)  NOT NULL DEFAULT 0,
    cost_price   DECIMAL(15,2)  NOT NULL DEFAULT 0,
    total_stock  INT            NOT NULL DEFAULT 0,
    min_stock_alert INT         NOT NULL DEFAULT 5,
    image_url    VARCHAR(500)   NULL,
    status       VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE'
                     CONSTRAINT chk_product_status
                     CHECK (status IN ('ACTIVE','OUT_OF_STOCK','DRAFT','LOW_STOCK','ARCHIVED')),
    created_at   DATETIME(3)    NOT NULL DEFAULT NOW(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT NOW(3) ON UPDATE NOW(3),
    deleted_at   DATETIME(3)    NULL,
    PRIMARY KEY (id),
    INDEX idx_products_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- product_variants
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS product_variants (
    id             CHAR(36)      NOT NULL,
    product_id     CHAR(36)      NOT NULL,
    tenant_id      CHAR(36)      NOT NULL,
    sku            VARCHAR(100)  NOT NULL,
    variant_name   VARCHAR(100)  NULL,
    price          DECIMAL(15,2) NULL,
    stock_quantity INT           NOT NULL DEFAULT 0,
    created_at     DATETIME(3)   NOT NULL DEFAULT NOW(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT NOW(3) ON UPDATE NOW(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_product_variants_tenant_sku (tenant_id, sku),
    CONSTRAINT fk_product_variants_product
        FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- orders
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS orders (
    id                    CHAR(36)      NOT NULL,
    tenant_id             CHAR(36)      NOT NULL,
    order_code            VARCHAR(50)   NOT NULL,
    external_order_id     VARCHAR(100)  NULL,
    marketplace           VARCHAR(50)   NOT NULL DEFAULT 'MANUAL',
    customer_name         VARCHAR(255)  NOT NULL,
    customer_phone        VARCHAR(30)   NULL,
    shipping_address_json JSON          NULL,
    total_amount          DECIMAL(15,2) NOT NULL DEFAULT 0,
    discount_amount       DECIMAL(15,2) NOT NULL DEFAULT 0,
    final_amount          DECIMAL(15,2) NOT NULL DEFAULT 0,
    payment_status        VARCHAR(20)   NOT NULL DEFAULT 'COD'
                              CONSTRAINT chk_order_payment_status
                              CHECK (payment_status IN ('PAID','COD','REFUNDED')),
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                              CONSTRAINT chk_order_status
                              CHECK (status IN ('PENDING','PACKED','IN_TRANSIT','DELIVERED','CANCELLED','RETURNED')),
    created_at            DATETIME(3)   NOT NULL DEFAULT NOW(3),
    updated_at            DATETIME(3)   NOT NULL DEFAULT NOW(3) ON UPDATE NOW(3),
    deleted_at            DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_tenant_order_code (tenant_id, order_code),
    INDEX idx_orders_tenant_id (tenant_id),
    INDEX idx_orders_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- order_items
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS order_items (
    id           CHAR(36)      NOT NULL,
    order_id     CHAR(36)      NOT NULL,
    tenant_id    CHAR(36)      NOT NULL,
    product_name VARCHAR(255)  NOT NULL,
    sku          VARCHAR(100)  NULL,
    variant_name VARCHAR(100)  NULL,
    price        DECIMAL(15,2) NOT NULL,
    quantity     INT           NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    INDEX idx_order_items_order_id (order_id),
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────
-- shipments
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS shipments (
    id                CHAR(36)      NOT NULL,
    tenant_id         CHAR(36)      NOT NULL,
    order_id          CHAR(36)      NULL,
    waybill_code      VARCHAR(100)  NOT NULL,
    carrier_name      VARCHAR(100)  NULL,
    destination       VARCHAR(255)  NULL,
    cod_amount        DECIMAL(15,2) NOT NULL DEFAULT 0,
    latest_milestone  VARCHAR(255)  NULL,
    milestone_type    VARCHAR(20)   NOT NULL DEFAULT 'waiting'
                          CONSTRAINT chk_shipment_milestone_type
                          CHECK (milestone_type IN ('picked','transit','success','failed','waiting')),
    shipped_at        DATETIME(3)   NULL,
    delivered_at      DATETIME(3)   NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT NOW(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT NOW(3) ON UPDATE NOW(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_shipments_tenant_waybill (tenant_id, waybill_code),
    INDEX idx_shipments_tenant_id (tenant_id),
    CONSTRAINT fk_shipments_order
        FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
