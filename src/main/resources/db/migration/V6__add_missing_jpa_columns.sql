-- Add missing columns to support simplified JPA Entity mappings in local development

-- 1. Alter products table
ALTER TABLE products
  ADD COLUMN name VARCHAR(255) NOT NULL DEFAULT '',
  ADD COLUMN category VARCHAR(100) NULL,
  ADD COLUMN price DECIMAL(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN cost_price DECIMAL(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN total_stock INT NOT NULL DEFAULT 0,
  ADD COLUMN min_stock_alert INT NOT NULL DEFAULT 5,
  ADD COLUMN image_url VARCHAR(500) NULL;

-- 2. Alter product_variants table
ALTER TABLE product_variants
  ADD COLUMN stock_quantity INT NOT NULL DEFAULT 0;

-- 3. Alter orders table
ALTER TABLE orders
  ADD COLUMN customer_name VARCHAR(255) NOT NULL DEFAULT 'Unknown',
  ADD COLUMN customer_phone VARCHAR(30) NULL,
  ADD COLUMN marketplace VARCHAR(50) NOT NULL DEFAULT 'MANUAL';

-- 4. Alter order_items table
ALTER TABLE order_items
  ADD COLUMN product_name VARCHAR(255) NOT NULL DEFAULT '',
  ADD COLUMN sku VARCHAR(100) NULL,
  ADD COLUMN variant_name VARCHAR(100) NULL,
  ADD COLUMN price DECIMAL(15,2) NOT NULL DEFAULT 0;

-- 5. Alter shipments table
ALTER TABLE shipments
  ADD COLUMN waybill_code VARCHAR(100) NOT NULL DEFAULT '',
  ADD COLUMN carrier_name VARCHAR(100) NULL,
  ADD COLUMN destination VARCHAR(255) NULL,
  ADD COLUMN cod_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
  ADD COLUMN latest_milestone VARCHAR(255) NULL,
  ADD COLUMN milestone_type VARCHAR(20) NOT NULL DEFAULT 'waiting';
