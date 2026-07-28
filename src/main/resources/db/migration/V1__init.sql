-- ============================================================================
-- OmnichannelPOS Database
-- Target       : MySQL 8.0.16+
-- Database     : omnichannel_pos
-- Character set: utf8mb4
--
-- This is the canonical, multi-tenant database of OmnichannelPOS.
-- The two simulator databases are external systems. Do not add cross-database
-- foreign keys and do not read/write their tables from application services.
--
-- Covered domains:
--   * Platform owner, rented tenant accounts, RBAC and first-login password reset
--   * Supported marketplaces, OAuth state and connected shop credentials
--   * Unified customers, products, variants, orders, shipments and conversations
--   * Templates/macros with validity windows
--   * Webhook inbox, sync checkpoints, outbox, retry and idempotency
--   * AI Sale: language understanding, RAG, tool lookup, answer generation,
--     quality gate, post-purchase care, recommendations, human handoff, feedback
--   * Customer AI profiles, product interests and non-automatic segmentation
--   * Revenue/product analytics, daily email reports and cancel/return alerts
--
-- Security:
--   * Passwords are hashes; marketplace tokens are encrypted by the application.
--   * PII retention, encryption, redaction and AI-use rules are tenant policies.
--   * Sensitive platform-owner and credential screens must also be protected by
--     application authorization. Database tables alone are not an access-control UI.
--   * Audit payloads must be sanitized before insert.
--
-- Rerun policy:
--   * No DROP DATABASE/TABLE.
--   * CREATE TABLE IF NOT EXISTS.
--   * Stable reference/demo records use INSERT ... ON DUPLICATE KEY UPDATE.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS omnichannel_pos
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE omnichannel_pos;

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET time_zone = '+00:00';

-- ============================================================================
-- 1. Platform owner, tenants, subscriptions and login
-- ============================================================================

CREATE TABLE IF NOT EXISTS platform_admins (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  email VARCHAR(255) COLLATE utf8mb4_0900_ai_ci NOT NULL,
  password_hash VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
  password_algorithm VARCHAR(30) NOT NULL DEFAULT 'ARGON2ID',
  display_name VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  failed_login_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  locked_until DATETIME(3) NULL,
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_platform_admins_email (email),
  CONSTRAINT ck_platform_admins_algorithm
    CHECK (password_algorithm IN ('ARGON2ID', 'BCRYPT', 'SCRYPT', 'PBKDF2')),
  CONSTRAINT ck_platform_admins_status
    CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
) ENGINE=InnoDB COMMENT='System-owner accounts; only these accounts may use the tenant rental administration page';

CREATE TABLE IF NOT EXISTS tenants (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_code VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_name VARCHAR(255) NOT NULL,
  legal_name VARCHAR(255) NULL,
  contact_email VARCHAR(255) NULL,
  contact_phone_encrypted TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  timezone_name VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  default_currency CHAR(3) NOT NULL DEFAULT 'VND',
  settings_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  provisioned_by_admin_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  provisioned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  suspended_at DATETIME(3) NULL,
  closed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_tenants_code (tenant_code),
  KEY idx_tenants_status (status, updated_at),
  CONSTRAINT fk_tenants_provisioned_by
    FOREIGN KEY (provisioned_by_admin_id) REFERENCES platform_admins(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_tenants_status
    CHECK (status IN ('TRIAL', 'ACTIVE', 'SUSPENDED', 'CLOSED'))
) ENGINE=InnoDB COMMENT='Organizations renting OmnichannelPOS; tenant boundary for business data';

CREATE TABLE IF NOT EXISTS subscription_plans (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  plan_code VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  plan_name VARCHAR(150) NOT NULL,
  billing_period VARCHAR(20) NOT NULL,
  price_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency CHAR(3) NOT NULL DEFAULT 'VND',
  limits_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  features_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_subscription_plans_code (plan_code),
  CONSTRAINT ck_subscription_plans_period
    CHECK (billing_period IN ('MONTHLY', 'QUARTERLY', 'YEARLY', 'CUSTOM')),
  CONSTRAINT ck_subscription_plans_price CHECK (price_amount >= 0),
  CONSTRAINT ck_subscription_plans_status
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB COMMENT='Rental plans managed by the platform owner';

CREATE TABLE IF NOT EXISTS tenant_subscriptions (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  subscription_plan_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'TRIAL',
  starts_at DATETIME(3) NOT NULL,
  trial_ends_at DATETIME(3) NULL,
  current_period_ends_at DATETIME(3) NULL,
  cancelled_at DATETIME(3) NULL,
  notes TEXT NULL,
  created_by_admin_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_tenant_subscriptions_tenant_status (tenant_id, status),
  KEY idx_tenant_subscriptions_expiry (status, current_period_ends_at),
  CONSTRAINT fk_tenant_subscriptions_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_tenant_subscriptions_plan
    FOREIGN KEY (subscription_plan_id) REFERENCES subscription_plans(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_tenant_subscriptions_admin
    FOREIGN KEY (created_by_admin_id) REFERENCES platform_admins(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_tenant_subscriptions_status
    CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED', 'EXPIRED')),
  CONSTRAINT ck_tenant_subscriptions_dates CHECK (
    (trial_ends_at IS NULL OR trial_ends_at >= starts_at)
    AND (current_period_ends_at IS NULL OR current_period_ends_at >= starts_at)
  )
) ENGINE=InnoDB COMMENT='Rental/account lifecycle visible only to platform-owner administration';

CREATE TABLE IF NOT EXISTS tenant_users (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  email VARCHAR(255) COLLATE utf8mb4_0900_ai_ci NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  avatar_url TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'INVITED',
  locale VARCHAR(20) NOT NULL DEFAULT 'vi-VN',
  provisioned_by_admin_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  provisioned_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_tenant_users_tenant_email (tenant_id, email),
  UNIQUE KEY uq_tenant_users_id_tenant (id, tenant_id),
  KEY idx_tenant_users_status (tenant_id, status),
  CONSTRAINT fk_tenant_users_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_tenant_users_admin
    FOREIGN KEY (provisioned_by_admin_id) REFERENCES platform_admins(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT fk_tenant_users_user
    FOREIGN KEY (provisioned_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_tenant_users_status
    CHECK (status IN ('INVITED', 'ACTIVE', 'LOCKED', 'SUSPENDED', 'DISABLED'))
) ENGINE=InnoDB COMMENT='Employees/managers who log in under exactly one tenant';

CREATE TABLE IF NOT EXISTS tenant_user_credentials (
  tenant_user_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  password_hash VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
  password_algorithm VARCHAR(30) NOT NULL DEFAULT 'ARGON2ID',
  must_change_password TINYINT(1) NOT NULL DEFAULT 1,
  password_changed_at DATETIME(3) NULL,
  password_expires_at DATETIME(3) NULL,
  failed_login_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  locked_until DATETIME(3) NULL,
  reset_token_hash CHAR(64) COLLATE utf8mb4_0900_bin NULL,
  reset_token_expires_at DATETIME(3) NULL,
  credential_version INT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_user_id),
  UNIQUE KEY uq_tenant_user_credentials_reset (reset_token_hash),
  CONSTRAINT fk_tenant_user_credentials_user
    FOREIGN KEY (tenant_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_tenant_user_credentials_algorithm
    CHECK (password_algorithm IN ('ARGON2ID', 'BCRYPT', 'SCRYPT', 'PBKDF2')),
  CONSTRAINT ck_tenant_user_credentials_must_change
    CHECK (must_change_password IN (0, 1)),
  CONSTRAINT ck_tenant_user_credentials_version CHECK (credential_version > 0)
) ENGINE=InnoDB COMMENT='Tenant login secrets and mandatory first-login password-change flag';

CREATE TABLE IF NOT EXISTS tenant_password_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_user_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  password_hash VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
  password_algorithm VARCHAR(30) NOT NULL,
  changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  changed_by_type VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_tenant_password_history_user_time (tenant_user_id, changed_at DESC),
  CONSTRAINT fk_tenant_password_history_user
    FOREIGN KEY (tenant_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_tenant_password_history_actor
    CHECK (changed_by_type IN ('PLATFORM_ADMIN', 'TENANT_USER', 'SYSTEM_RESET'))
) ENGINE=InnoDB COMMENT='Recent password hashes used to prevent password reuse';

CREATE TABLE IF NOT EXISTS roles (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NULL COMMENT 'NULL for immutable system roles',
  tenant_scope_key CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL
    DEFAULT '00000000-0000-0000-0000-000000000000',
  role_code VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  role_name VARCHAR(100) NOT NULL,
  description TEXT NULL,
  is_system TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_roles_id_scope (id, tenant_scope_key),
  UNIQUE KEY uq_roles_scope_code (tenant_scope_key, role_code),
  CONSTRAINT fk_roles_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_roles_system CHECK (is_system IN (0, 1)),
  CONSTRAINT ck_roles_scope CHECK (
    (
      tenant_id IS NULL
      AND tenant_scope_key = '00000000-0000-0000-0000-000000000000'
      AND is_system = 1
    )
    OR (
      tenant_id IS NOT NULL
      AND tenant_scope_key = tenant_id
    )
  )
) ENGINE=InnoDB COMMENT='Tenant-scoped and system tenant-user roles';

CREATE TABLE IF NOT EXISTS permissions (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  permission_code VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  permission_name VARCHAR(150) NOT NULL,
  resource_code VARCHAR(50) NOT NULL,
  action_code VARCHAR(50) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_permissions_code (permission_code)
) ENGINE=InnoDB COMMENT='Atomic application permissions';

CREATE TABLE IF NOT EXISTS role_permissions (
  role_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  permission_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_role_permissions_role
    FOREIGN KEY (role_id) REFERENCES roles(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_role_permissions_permission
    FOREIGN KEY (permission_id) REFERENCES permissions(id)
    ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='Permissions granted to a role';

CREATE TABLE IF NOT EXISTS tenant_user_roles (
  tenant_user_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  role_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  role_scope_key CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  assigned_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_user_id, role_id),
  KEY idx_tenant_user_roles_tenant (tenant_id, role_id),
  CONSTRAINT fk_tenant_user_roles_user_tenant
    FOREIGN KEY (tenant_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_tenant_user_roles_role_scope
    FOREIGN KEY (role_id, role_scope_key) REFERENCES roles(id, tenant_scope_key)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_tenant_user_roles_assigner_tenant
    FOREIGN KEY (assigned_by_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_tenant_user_roles_scope CHECK (
    role_scope_key = tenant_id
    OR role_scope_key = '00000000-0000-0000-0000-000000000000'
  )
) ENGINE=InnoDB COMMENT='Role assignments for tenant users';

CREATE TABLE IF NOT EXISTS login_sessions (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  actor_type VARCHAR(20) NOT NULL,
  platform_admin_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  tenant_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  session_token_hash CHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  auth_stage VARCHAR(30) NOT NULL DEFAULT 'AUTHENTICATED',
  ip_address VARCHAR(45) NULL,
  user_agent VARCHAR(500) NULL,
  issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_login_sessions_token (session_token_hash),
  KEY idx_login_sessions_tenant_user (tenant_user_id, expires_at),
  KEY idx_login_sessions_admin (platform_admin_id, expires_at),
  CONSTRAINT fk_login_sessions_admin
    FOREIGN KEY (platform_admin_id) REFERENCES platform_admins(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_login_sessions_user
    FOREIGN KEY (tenant_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_login_sessions_actor
    CHECK (actor_type IN ('PLATFORM_ADMIN', 'TENANT_USER')),
  CONSTRAINT ck_login_sessions_actor_ref CHECK (
    (actor_type = 'PLATFORM_ADMIN' AND platform_admin_id IS NOT NULL AND tenant_user_id IS NULL)
    OR
    (actor_type = 'TENANT_USER' AND tenant_user_id IS NOT NULL AND platform_admin_id IS NULL)
  ),
  CONSTRAINT ck_login_sessions_auth_stage
    CHECK (auth_stage IN ('PASSWORD_CHANGE_REQUIRED', 'AUTHENTICATED', 'MFA_REQUIRED')),
  CONSTRAINT ck_login_sessions_expiry CHECK (expires_at > issued_at)
) ENGINE=InnoDB COMMENT='Hashed sessions for both platform owner and tenant users';

CREATE TABLE IF NOT EXISTS security_audit_logs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  actor_type VARCHAR(20) NOT NULL,
  actor_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  action_code VARCHAR(100) NOT NULL,
  target_type VARCHAR(50) NULL,
  target_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  result VARCHAR(20) NOT NULL,
  ip_address VARCHAR(45) NULL,
  metadata_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_security_audit_tenant_time (tenant_id, occurred_at DESC),
  KEY idx_security_audit_actor_time (actor_type, actor_id, occurred_at DESC),
  KEY idx_security_audit_action_time (action_code, occurred_at DESC),
  CONSTRAINT fk_security_audit_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_security_audit_actor
    CHECK (actor_type IN ('PLATFORM_ADMIN', 'TENANT_USER', 'SYSTEM', 'AI')),
  CONSTRAINT ck_security_audit_result
    CHECK (result IN ('SUCCEEDED', 'FAILED', 'DENIED'))
) ENGINE=InnoDB COMMENT='Sanitized audit trail including tenant provisioning and access denials';

CREATE TABLE IF NOT EXISTS data_protection_policies (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  data_category VARCHAR(50) NOT NULL,
  retention_days INT UNSIGNED NOT NULL,
  encrypt_at_rest TINYINT(1) NOT NULL DEFAULT 1,
  redact_in_logs TINYINT(1) NOT NULL DEFAULT 1,
  allow_ai_processing TINYINT(1) NOT NULL DEFAULT 0,
  purge_enabled TINYINT(1) NOT NULL DEFAULT 1,
  policy_version VARCHAR(30) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_data_protection_policy_category (tenant_id, data_category),
  CONSTRAINT fk_data_protection_policy_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_data_protection_policy_category CHECK (
    data_category IN (
      'CUSTOMER_CONTACT', 'ORDER_ADDRESS', 'MESSAGE_CONTENT', 'RAW_PAYLOAD',
      'WEBHOOK_PAYLOAD', 'API_AUDIT', 'AI_CONTEXT', 'AI_OUTPUT'
    )
  ),
  CONSTRAINT ck_data_protection_policy_flags CHECK (
    encrypt_at_rest IN (0, 1)
    AND redact_in_logs IN (0, 1)
    AND allow_ai_processing IN (0, 1)
    AND purge_enabled IN (0, 1)
  ),
  CONSTRAINT ck_data_protection_policy_retention CHECK (retention_days > 0)
) ENGINE=InnoDB COMMENT='Tenant PII encryption, redaction, AI-use and retention policy by data category';

-- ============================================================================
-- 2. Supported marketplaces and connected shops
-- ============================================================================

CREATE TABLE IF NOT EXISTS marketplaces (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_code VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_name VARCHAR(100) NOT NULL,
  adapter_code VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  mock_base_url VARCHAR(500) NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  capabilities_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_marketplaces_code (marketplace_code),
  UNIQUE KEY uq_marketplaces_adapter (adapter_code),
  CONSTRAINT ck_marketplaces_code
    CHECK (marketplace_code IN ('TIKTOK_SHOP', 'LAZADA')),
  CONSTRAINT ck_marketplaces_active CHECK (is_active IN (0, 1))
) ENGINE=InnoDB COMMENT='Allow-list of marketplace adapters; initially only two simulators';

CREATE TABLE IF NOT EXISTS marketplace_accounts (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  external_account_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  shop_cipher VARCHAR(255) COLLATE utf8mb4_0900_bin NULL,
  external_shop_name VARCHAR(255) NOT NULL,
  site_id VARCHAR(10) NOT NULL DEFAULT 'VN',
  currency CHAR(3) NOT NULL DEFAULT 'VND',
  timezone_name VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  connection_status VARCHAR(20) NOT NULL DEFAULT 'CONNECTED',
  authorized_at DATETIME(3) NULL,
  expires_at DATETIME(3) NULL,
  last_verified_at DATETIME(3) NULL,
  settings_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_marketplace_accounts_scope (tenant_id, marketplace_id, external_account_id),
  UNIQUE KEY uq_marketplace_accounts_id_tenant (id, tenant_id),
  KEY idx_marketplace_accounts_tenant_status (tenant_id, connection_status),
  KEY idx_marketplace_accounts_expiry (connection_status, expires_at),
  CONSTRAINT fk_marketplace_accounts_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_marketplace_accounts_marketplace
    FOREIGN KEY (marketplace_id) REFERENCES marketplaces(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_marketplace_accounts_creator_tenant
    FOREIGN KEY (created_by_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_marketplace_accounts_status
    CHECK (connection_status IN ('CONNECTED', 'EXPIRED', 'REVOKED', 'ERROR', 'DISABLED'))
) ENGINE=InnoDB COMMENT='TikTok shops and Lazada sellers connected by a tenant';

CREATE TABLE IF NOT EXISTS marketplace_credentials (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  app_key VARCHAR(150) COLLATE utf8mb4_0900_bin NULL,
  access_token_encrypted MEDIUMTEXT NOT NULL,
  refresh_token_encrypted MEDIUMTEXT NULL,
  signing_secret_encrypted MEDIUMTEXT NULL,
  scopes_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
  encryption_key_version VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL,
  access_token_expires_at DATETIME(3) NULL,
  refresh_token_expires_at DATETIME(3) NULL,
  last_refreshed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_marketplace_credentials_account (marketplace_account_id),
  CONSTRAINT fk_marketplace_credentials_account
    FOREIGN KEY (marketplace_account_id) REFERENCES marketplace_accounts(id)
    ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='Encrypted credentials; application KMS owns encryption keys';

CREATE TABLE IF NOT EXISTS marketplace_connection_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status VARCHAR(20) NOT NULL,
  reason_code VARCHAR(100) NULL,
  details_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  changed_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_marketplace_connection_history_account_time
    (marketplace_account_id, occurred_at DESC),
  CONSTRAINT fk_marketplace_connection_history_account
    FOREIGN KEY (marketplace_account_id) REFERENCES marketplace_accounts(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_marketplace_connection_history_user
    FOREIGN KEY (changed_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Authorization/connection lifecycle audit';

CREATE TABLE IF NOT EXISTS oauth_authorization_sessions (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_user_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  state_hash CHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  pkce_verifier_encrypted MEDIUMTEXT NULL,
  requested_scopes_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
  return_url VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  expires_at DATETIME(3) NOT NULL,
  consumed_at DATETIME(3) NULL,
  failure_code VARCHAR(100) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_oauth_authorization_sessions_state (state_hash),
  KEY idx_oauth_authorization_sessions_expiry (status, expires_at),
  CONSTRAINT fk_oauth_authorization_sessions_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_oauth_authorization_sessions_user_tenant
    FOREIGN KEY (tenant_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_oauth_authorization_sessions_marketplace
    FOREIGN KEY (marketplace_id) REFERENCES marketplaces(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_oauth_authorization_sessions_status
    CHECK (status IN ('PENDING', 'AUTHORIZED', 'DENIED', 'EXPIRED', 'FAILED')),
  CONSTRAINT ck_oauth_authorization_sessions_expiry
    CHECK (expires_at > created_at),
  CONSTRAINT ck_oauth_authorization_sessions_consumed CHECK (
    status = 'PENDING' OR consumed_at IS NOT NULL
  )
) ENGINE=InnoDB COMMENT='Single-use OAuth state/PKCE session bound to the initiating tenant user';

-- ============================================================================
-- 3. Customers, identity isolation and behavioral interests
-- ============================================================================

CREATE TABLE IF NOT EXISTS customers (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  customer_code VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  display_name VARCHAR(255) NULL,
  phone_normalized_encrypted TEXT NULL,
  email_normalized_encrypted TEXT NULL,
  phone_lookup_hmac CHAR(64) COLLATE utf8mb4_0900_bin NULL,
  email_lookup_hmac CHAR(64) COLLATE utf8mb4_0900_bin NULL,
  pii_key_version VARCHAR(30) COLLATE utf8mb4_0900_bin NULL,
  identity_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_customers_tenant_code (tenant_id, customer_code),
  UNIQUE KEY uq_customers_id_tenant (id, tenant_id),
  UNIQUE KEY uq_customers_tenant_phone_lookup (tenant_id, phone_lookup_hmac),
  UNIQUE KEY uq_customers_tenant_email_lookup (tenant_id, email_lookup_hmac),
  KEY idx_customers_tenant_status (tenant_id, identity_status, updated_at),
  CONSTRAINT fk_customers_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_customers_identity_status
    CHECK (identity_status IN ('UNVERIFIED', 'VERIFIED', 'MERGED'))
) ENGINE=InnoDB COMMENT='Optional verified cross-channel customer profile; never auto-created from a matching name';

CREATE TABLE IF NOT EXISTS marketplace_customers (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  external_customer_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  external_im_user_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  display_name VARCHAR(255) NULL,
  avatar_url TEXT NULL,
  phone_masked VARCHAR(100) NULL,
  email_masked VARCHAR(255) NULL,
  raw_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  first_seen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_marketplace_customers_account_external
    (marketplace_account_id, external_customer_id),
  UNIQUE KEY uq_marketplace_customers_id_tenant (id, tenant_id),
  KEY idx_marketplace_customers_tenant_seen (tenant_id, last_seen_at DESC),
  KEY idx_marketplace_customers_im (marketplace_account_id, external_im_user_id),
  CONSTRAINT fk_marketplace_customers_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_marketplace_customers_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Buyer identity remains separate per connected marketplace account';

CREATE TABLE IF NOT EXISTS customer_identity_links (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  customer_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  link_method VARCHAR(30) NOT NULL,
  verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  verified_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  verified_at DATETIME(3) NULL,
  evidence_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_customer_identity_links_marketplace_customer (marketplace_customer_id),
  KEY idx_customer_identity_links_customer (customer_id, verification_status),
  CONSTRAINT fk_customer_identity_links_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_customer_identity_links_customer_tenant
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_customer_identity_links_marketplace_customer_tenant
    FOREIGN KEY (marketplace_customer_id, tenant_id)
    REFERENCES marketplace_customers(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_customer_identity_links_verifier_tenant
    FOREIGN KEY (verified_by_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_customer_identity_links_method
    CHECK (link_method IN ('MANUAL', 'OTP', 'EMAIL', 'PHONE', 'TEST_SEED')),
  CONSTRAINT ck_customer_identity_links_status
    CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED'))
) ENGINE=InnoDB COMMENT='Explicitly verified identity links; names and masked contact fields are never sufficient';

CREATE TABLE IF NOT EXISTS customer_behavior_events (
  event_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  source_session_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
  marketplace_code VARCHAR(30) NOT NULL,
  event_name VARCHAR(50) NOT NULL,
  screen VARCHAR(100) NULL,
  entity_type VARCHAR(50) NULL,
  entity_external_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  properties_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  occurred_at DATETIME(3) NOT NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (marketplace_account_id, event_id),
  KEY idx_customer_behavior_tenant_time (tenant_id, occurred_at DESC),
  KEY idx_customer_behavior_customer_time (marketplace_customer_id, occurred_at DESC),
  KEY idx_customer_behavior_entity (tenant_id, entity_type, entity_external_id, occurred_at),
  CONSTRAINT fk_customer_behavior_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_customer_behavior_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_customer_behavior_marketplace_customer_tenant
    FOREIGN KEY (marketplace_customer_id, tenant_id)
    REFERENCES marketplace_customers(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_customer_behavior_marketplace
    CHECK (marketplace_code IN ('TIKTOK_SHOP', 'LAZADA'))
) ENGINE=InnoDB COMMENT='Raw customer storefront behavior used as explainable interest/segment signals';

-- ============================================================================
-- 4. Canonical products, variants and marketplace mappings
-- ============================================================================

CREATE TABLE IF NOT EXISTS products (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_code VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  product_name VARCHAR(500) NOT NULL,
  description MEDIUMTEXT NULL,
  brand_name VARCHAR(255) NULL,
  internal_category_code VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  attributes_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  version INT UNSIGNED NOT NULL DEFAULT 1,
  created_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_products_tenant_code (tenant_id, product_code),
  UNIQUE KEY uq_products_id_tenant (id, tenant_id),
  KEY idx_products_tenant_status (tenant_id, status, updated_at DESC),
  KEY idx_products_tenant_name (tenant_id, product_name),
  CONSTRAINT fk_products_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_products_creator_tenant
    FOREIGN KEY (created_by_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_products_status
    CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_products_version CHECK (version > 0)
) ENGINE=InnoDB COMMENT='Canonical product edited from the centralized product screen';

CREATE TABLE IF NOT EXISTS product_variants (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  variant_code VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  seller_sku VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  variant_name VARCHAR(255) NULL,
  attributes_json JSON NOT NULL DEFAULT (JSON_OBJECT())
    COMMENT 'Examples: color, size, material',
  price DECIMAL(18,2) NOT NULL DEFAULT 0,
  compare_at_price DECIMAL(18,2) NULL,
  currency CHAR(3) NOT NULL DEFAULT 'VND',
  stock_on_hand INT NOT NULL DEFAULT 0,
  reserved_stock INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  version INT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_product_variants_product_code (product_id, variant_code),
  UNIQUE KEY uq_product_variants_tenant_sku (tenant_id, seller_sku),
  UNIQUE KEY uq_product_variants_id_tenant (id, tenant_id),
  KEY idx_product_variants_product_status (product_id, status),
  CONSTRAINT fk_product_variants_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_product_variants_product_tenant
    FOREIGN KEY (product_id, tenant_id) REFERENCES products(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_product_variants_amounts CHECK (
    price >= 0 AND (compare_at_price IS NULL OR compare_at_price >= 0)
  ),
  CONSTRAINT ck_product_variants_stock CHECK (
    stock_on_hand >= 0 AND reserved_stock >= 0 AND reserved_stock <= stock_on_hand
  ),
  CONSTRAINT ck_product_variants_status
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_product_variants_version CHECK (version > 0)
) ENGINE=InnoDB COMMENT='Canonical size/color/etc. variants and tenant seller SKUs';

CREATE TABLE IF NOT EXISTS product_media (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_variant_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  media_type VARCHAR(20) NOT NULL,
  storage_key VARCHAR(500) COLLATE utf8mb4_0900_bin NOT NULL,
  public_url TEXT NOT NULL,
  checksum_sha256 CHAR(64) COLLATE utf8mb4_0900_bin NULL,
  sort_order INT UNSIGNED NOT NULL DEFAULT 0,
  is_primary TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_product_media_id_tenant (id, tenant_id),
  KEY idx_product_media_product_sort (product_id, sort_order),
  KEY idx_product_media_variant (product_variant_id),
  CONSTRAINT fk_product_media_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_product_media_product_tenant
    FOREIGN KEY (product_id, tenant_id) REFERENCES products(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_product_media_variant_tenant
    FOREIGN KEY (product_variant_id, tenant_id)
    REFERENCES product_variants(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_product_media_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
  CONSTRAINT ck_product_media_primary CHECK (is_primary IN (0, 1))
) ENGINE=InnoDB COMMENT='Canonical product and variant media';

CREATE TABLE IF NOT EXISTS marketplace_products (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  external_product_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  external_category_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  external_title VARCHAR(500) NULL,
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  sync_status VARCHAR(20) NOT NULL DEFAULT 'SYNCED',
  external_version VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
  raw_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  last_synced_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_marketplace_products_external
    (marketplace_account_id, external_product_id),
  UNIQUE KEY uq_marketplace_products_mapping (product_id, marketplace_account_id),
  UNIQUE KEY uq_marketplace_products_id_tenant (id, tenant_id),
  KEY idx_marketplace_products_sync
    (tenant_id, sync_status, updated_at),
  CONSTRAINT fk_marketplace_products_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_marketplace_products_product_tenant
    FOREIGN KEY (product_id, tenant_id) REFERENCES products(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_marketplace_products_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_marketplace_products_status
    CHECK (canonical_status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'DELETED', 'FAILED')),
  CONSTRAINT ck_marketplace_products_sync
    CHECK (sync_status IN ('SYNCED', 'PENDING', 'CONFLICT', 'ERROR'))
) ENGINE=InnoDB COMMENT='Mapping between a canonical product and each marketplace listing';

CREATE TABLE IF NOT EXISTS marketplace_product_variants (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_product_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_variant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  external_sku_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  external_seller_sku VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  external_price DECIMAL(18,2) NULL,
  external_stock INT NULL,
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  sync_status VARCHAR(20) NOT NULL DEFAULT 'SYNCED',
  raw_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  last_synced_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_marketplace_product_variants_external
    (marketplace_product_id, external_sku_id),
  UNIQUE KEY uq_marketplace_product_variants_mapping
    (marketplace_product_id, product_variant_id),
  UNIQUE KEY uq_marketplace_product_variants_id_tenant (id, tenant_id),
  KEY idx_marketplace_product_variants_sync (tenant_id, sync_status, updated_at),
  CONSTRAINT fk_marketplace_product_variants_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_marketplace_product_variants_product_tenant
    FOREIGN KEY (marketplace_product_id, tenant_id)
    REFERENCES marketplace_products(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_marketplace_product_variants_variant_tenant
    FOREIGN KEY (product_variant_id, tenant_id)
    REFERENCES product_variants(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_marketplace_product_variants_values CHECK (
    (external_price IS NULL OR external_price >= 0)
    AND (external_stock IS NULL OR external_stock >= 0)
  ),
  CONSTRAINT ck_marketplace_product_variants_sync
    CHECK (sync_status IN ('SYNCED', 'PENDING', 'CONFLICT', 'ERROR'))
) ENGINE=InnoDB COMMENT='Mapping between canonical variants and marketplace SKUs';

CREATE TABLE IF NOT EXISTS product_sync_history (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_product_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  direction VARCHAR(20) NOT NULL,
  change_type VARCHAR(30) NOT NULL,
  before_json JSON NULL,
  after_json JSON NULL,
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(100) NULL,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_product_sync_history_product_time
    (marketplace_product_id, occurred_at DESC),
  KEY idx_product_sync_history_tenant_status
    (tenant_id, status, occurred_at),
  CONSTRAINT fk_product_sync_history_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_product_sync_history_product
    FOREIGN KEY (marketplace_product_id) REFERENCES marketplace_products(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_product_sync_history_direction
    CHECK (direction IN ('MARKETPLACE_TO_OMNI', 'OMNI_TO_MARKETPLACE')),
  CONSTRAINT ck_product_sync_history_change
    CHECK (change_type IN ('CREATE', 'UPDATE', 'DELETE', 'PRICE', 'INVENTORY')),
  CONSTRAINT ck_product_sync_history_status
    CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CONFLICT'))
) ENGINE=InnoDB COMMENT='Auditable centralized product CRUD and marketplace synchronization';

-- ============================================================================
-- 5. Canonical orders, order items, shipments and marketplace actions
-- ============================================================================

CREATE TABLE IF NOT EXISTS orders (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  external_order_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  payment_status VARCHAR(30) NOT NULL DEFAULT 'UNPAID',
  refund_status VARCHAR(30) NOT NULL DEFAULT 'NONE',
  currency CHAR(3) NOT NULL DEFAULT 'VND',
  subtotal_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  shipping_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  shipping_address_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  billing_address_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  shipping_address_encrypted MEDIUMTEXT NULL,
  billing_address_encrypted MEDIUMTEXT NULL,
  pii_key_version VARCHAR(30) COLLATE utf8mb4_0900_bin NULL,
  buyer_note TEXT NULL,
  internal_note TEXT NULL,
  raw_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  external_created_at DATETIME(3) NOT NULL,
  external_updated_at DATETIME(3) NOT NULL,
  last_synced_at DATETIME(3) NOT NULL,
  version INT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_orders_external (marketplace_account_id, external_order_id),
  UNIQUE KEY uq_orders_id_tenant (id, tenant_id),
  KEY idx_orders_tenant_status_time
    (tenant_id, canonical_status, external_created_at DESC),
  KEY idx_orders_account_status_time
    (marketplace_account_id, canonical_status, external_created_at DESC),
  KEY idx_orders_customer_time
    (marketplace_customer_id, external_created_at DESC),
  CONSTRAINT fk_orders_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_orders_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_orders_customer_tenant
    FOREIGN KEY (marketplace_customer_id, tenant_id)
    REFERENCES marketplace_customers(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_orders_canonical_status CHECK (
    canonical_status IN (
      'CREATED', 'CONFIRMED', 'READY_TO_SHIP', 'SHIPPED', 'IN_TRANSIT',
      'DELIVERED', 'CANCELLED', 'RETURN_REQUESTED', 'RETURNED', 'FAILED'
    )
  ),
  CONSTRAINT ck_orders_payment_status
    CHECK (payment_status IN ('UNPAID', 'PAID', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED')),
  CONSTRAINT ck_orders_refund_status
    CHECK (refund_status IN ('NONE', 'REQUESTED', 'PROCESSING', 'PARTIAL', 'COMPLETED', 'REJECTED')),
  CONSTRAINT ck_orders_amounts CHECK (
    subtotal_amount >= 0 AND shipping_amount >= 0 AND discount_amount >= 0
    AND tax_amount >= 0 AND total_amount >= 0
  ),
  CONSTRAINT ck_orders_version CHECK (version > 0)
) ENGINE=InnoDB COMMENT='Marketplace orders normalized to the ten required Omnichannel statuses';

CREATE TABLE IF NOT EXISTS order_items (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  order_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  product_variant_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  marketplace_product_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  marketplace_product_variant_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  external_order_item_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  external_product_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  external_sku_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  seller_sku_snapshot VARCHAR(200) NULL,
  product_name_snapshot VARCHAR(500) NOT NULL,
  variant_name_snapshot VARCHAR(255) NULL,
  quantity INT UNSIGNED NOT NULL,
  unit_price DECIMAL(18,2) NOT NULL,
  discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  paid_amount DECIMAL(18,2) NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'VND',
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  raw_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_order_items_external (order_id, external_order_item_id),
  UNIQUE KEY uq_order_items_id_tenant (id, tenant_id),
  KEY idx_order_items_product_time (product_id, created_at),
  KEY idx_order_items_variant (product_variant_id),
  CONSTRAINT fk_order_items_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_items_order_tenant
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_items_product_tenant
    FOREIGN KEY (product_id, tenant_id) REFERENCES products(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_items_variant_tenant
    FOREIGN KEY (product_variant_id, tenant_id)
    REFERENCES product_variants(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_items_marketplace_product_tenant
    FOREIGN KEY (marketplace_product_id, tenant_id)
    REFERENCES marketplace_products(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_items_marketplace_variant_tenant
    FOREIGN KEY (marketplace_product_variant_id, tenant_id)
    REFERENCES marketplace_product_variants(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
  CONSTRAINT ck_order_items_amounts
    CHECK (unit_price >= 0 AND discount_amount >= 0 AND paid_amount >= 0),
  CONSTRAINT ck_order_items_status CHECK (
    canonical_status IN (
      'CREATED', 'CONFIRMED', 'READY_TO_SHIP', 'SHIPPED', 'IN_TRANSIT',
      'DELIVERED', 'CANCELLED', 'RETURN_REQUESTED', 'RETURNED', 'FAILED'
    )
  )
) ENGINE=InnoDB COMMENT='Immutable commercial snapshots for order detail and product analytics';

CREATE TABLE IF NOT EXISTS shipments (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  order_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  external_package_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  tracking_number VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  shipping_provider VARCHAR(150) NULL,
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  shipping_label_url TEXT NULL,
  package_items_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
  ready_to_ship_at DATETIME(3) NULL,
  shipped_at DATETIME(3) NULL,
  delivered_at DATETIME(3) NULL,
  raw_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_shipments_external (order_id, external_package_id),
  UNIQUE KEY uq_shipments_id_tenant (id, tenant_id),
  KEY idx_shipments_tracking (tenant_id, tracking_number),
  KEY idx_shipments_order_status (order_id, canonical_status),
  CONSTRAINT fk_shipments_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_shipments_order_tenant
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_shipments_status CHECK (
    canonical_status IN (
      'CREATED', 'READY_TO_SHIP', 'SHIPPED', 'IN_TRANSIT',
      'DELIVERED', 'RETURNED', 'FAILED', 'CANCELLED'
    )
  )
) ENGINE=InnoDB COMMENT='Packages and tracking data available to staff and AI order lookup';

CREATE TABLE IF NOT EXISTS order_status_history (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  order_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  order_item_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  from_raw_status VARCHAR(100) NULL,
  to_raw_status VARCHAR(100) NOT NULL,
  from_canonical_status VARCHAR(30) NULL,
  to_canonical_status VARCHAR(30) NOT NULL,
  source VARCHAR(30) NOT NULL,
  external_event_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  changed_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  reason_code VARCHAR(100) NULL,
  occurred_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_order_status_history_external
    (order_id, external_event_id),
  KEY idx_order_status_history_order_time (order_id, occurred_at),
  KEY idx_order_status_history_tenant_time (tenant_id, occurred_at),
  CONSTRAINT fk_order_status_history_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_status_history_order_tenant
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_status_history_item_tenant
    FOREIGN KEY (order_item_id, tenant_id) REFERENCES order_items(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_status_history_user_tenant
    FOREIGN KEY (changed_by_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_order_status_history_source
    CHECK (source IN ('WEBHOOK', 'POLLING', 'USER_ACTION', 'SYSTEM', 'RECONCILIATION'))
) ENGINE=InnoDB COMMENT='Append-only raw and canonical order status history';

CREATE TABLE IF NOT EXISTS order_actions (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  order_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  order_item_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  requested_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  action_type VARCHAR(40) NOT NULL,
  action_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  reason_code VARCHAR(100) NULL,
  reason_text TEXT NULL,
  request_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  response_payload JSON NULL,
  integration_operation_id CHAR(36) COLLATE utf8mb4_0900_bin NULL
    COMMENT 'Logical reference to outbound_operations; FK omitted to avoid a creation cycle',
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_order_actions_order_time (order_id, requested_at DESC),
  KEY idx_order_actions_tenant_status (tenant_id, action_status, requested_at),
  KEY idx_order_actions_operation (integration_operation_id),
  CONSTRAINT fk_order_actions_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_actions_order_tenant
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_actions_item_tenant
    FOREIGN KEY (order_item_id, tenant_id) REFERENCES order_items(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_order_actions_user_tenant
    FOREIGN KEY (requested_by_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_order_actions_type
    CHECK (action_type IN ('CREATE', 'UPDATE', 'DELETE', 'CONFIRM', 'PACK', 'READY_TO_SHIP', 'CANCEL', 'REJECT_CANCEL', 'ACCEPT_RETURN', 'REJECT_RETURN')),
  CONSTRAINT ck_order_actions_status
    CHECK (action_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED')),
  CONSTRAINT ck_order_actions_completed CHECK (
    action_status NOT IN ('SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED')
    OR completed_at IS NOT NULL
  )
) ENGINE=InnoDB COMMENT='Order CRUD/fulfillment commands that must be synchronized back to the marketplace';

-- ============================================================================
-- 6. Unified Inbox, messages, assignment and time-limited macros/templates
-- ============================================================================

CREATE TABLE IF NOT EXISTS conversations (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  external_conversation_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  raw_status VARCHAR(100) NULL,
  internal_status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  assigned_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  unread_count INT UNSIGNED NOT NULL DEFAULT 0,
  last_message_id CHAR(36) COLLATE utf8mb4_0900_bin NULL
    COMMENT 'Logical reference to messages.id; application maintains it after message insert',
  last_message_preview VARCHAR(500) NULL,
  last_message_at DATETIME(3) NULL,
  ai_mode VARCHAR(20) NOT NULL DEFAULT 'AUTO',
  raw_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  closed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_conversations_external
    (marketplace_account_id, external_conversation_id),
  UNIQUE KEY uq_conversations_id_tenant (id, tenant_id),
  KEY idx_conversations_unified_inbox
    (tenant_id, internal_status, priority, last_message_at DESC),
  KEY idx_conversations_marketplace_filter
    (marketplace_account_id, internal_status, last_message_at DESC),
  KEY idx_conversations_assignee
    (assigned_user_id, internal_status, last_message_at DESC),
  KEY idx_conversations_customer
    (marketplace_customer_id, last_message_at DESC),
  CONSTRAINT fk_conversations_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_conversations_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_conversations_customer_tenant
    FOREIGN KEY (marketplace_customer_id, tenant_id)
    REFERENCES marketplace_customers(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_conversations_assignee_tenant
    FOREIGN KEY (assigned_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_conversations_status CHECK (
    internal_status IN (
      'NEW', 'OPEN', 'PENDING_CUSTOMER', 'PENDING_INTERNAL',
      'RESOLVED', 'CLOSED', 'SPAM'
    )
  ),
  CONSTRAINT ck_conversations_priority
    CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
  CONSTRAINT ck_conversations_ai_mode
    CHECK (ai_mode IN ('AUTO', 'SUGGEST_ONLY', 'HUMAN_ONLY', 'PAUSED'))
) ENGINE=InnoDB COMMENT='Unified Inbox rows filterable by tenant, marketplace, status and assignee';

CREATE TABLE IF NOT EXISTS messages (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  external_message_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  client_message_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  direction VARCHAR(10) NOT NULL,
  sender_type VARCHAR(20) NOT NULL,
  sender_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  message_type VARCHAR(30) NOT NULL,
  detected_language VARCHAR(20) NULL,
  text_content MEDIUMTEXT NULL,
  content_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  raw_payload JSON NOT NULL DEFAULT (JSON_OBJECT()),
  delivery_status VARCHAR(20) NOT NULL,
  moderation_status VARCHAR(20) NOT NULL DEFAULT 'NOT_CHECKED',
  error_code VARCHAR(100) NULL,
  error_message TEXT NULL,
  queued_at DATETIME(3) NULL,
  sent_at DATETIME(3) NULL,
  delivered_at DATETIME(3) NULL,
  read_at DATETIME(3) NULL,
  failed_at DATETIME(3) NULL,
  recalled_at DATETIME(3) NULL,
  external_created_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_messages_external (conversation_id, external_message_id),
  UNIQUE KEY uq_messages_client (conversation_id, client_message_id),
  UNIQUE KEY uq_messages_id_tenant (id, tenant_id),
  KEY idx_messages_conversation_time (conversation_id, created_at),
  KEY idx_messages_tenant_direction_time (tenant_id, direction, created_at),
  CONSTRAINT fk_messages_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_messages_conversation_tenant
    FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversations(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_messages_sender_tenant
    FOREIGN KEY (sender_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_messages_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
  CONSTRAINT ck_messages_sender
    CHECK (sender_type IN ('CUSTOMER', 'STAFF', 'AI', 'SYSTEM', 'SHOP')),
  CONSTRAINT ck_messages_delivery
    CHECK (delivery_status IN ('RECEIVED', 'QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'BLOCKED', 'RECALLED')),
  CONSTRAINT ck_messages_outbound_client_id CHECK (
    direction <> 'OUTBOUND' OR client_message_id IS NOT NULL
  ),
  CONSTRAINT ck_messages_delivery_timestamps CHECK (
    (delivery_status <> 'QUEUED' OR queued_at IS NOT NULL)
    AND (delivery_status <> 'SENT' OR sent_at IS NOT NULL)
    AND (delivery_status <> 'DELIVERED' OR delivered_at IS NOT NULL)
    AND (delivery_status <> 'READ' OR read_at IS NOT NULL)
    AND (delivery_status <> 'FAILED' OR failed_at IS NOT NULL)
    AND (delivery_status <> 'RECALLED' OR recalled_at IS NOT NULL)
  ),
  CONSTRAINT ck_messages_moderation
    CHECK (moderation_status IN ('NOT_CHECKED', 'PASSED', 'BLOCKED', 'NEEDS_REVIEW'))
) ENGINE=InnoDB COMMENT='Normalized inbound and outbound messages; one row is the AI trigger or final response';

CREATE TABLE IF NOT EXISTS message_attachments (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  message_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  attachment_type VARCHAR(30) NOT NULL,
  external_resource_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  resource_url TEXT NULL,
  thumbnail_url TEXT NULL,
  mime_type VARCHAR(100) NULL,
  file_size_bytes BIGINT UNSIGNED NULL,
  metadata_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  sort_order INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_message_attachments_message_sort (message_id, sort_order),
  CONSTRAINT fk_message_attachments_message
    FOREIGN KEY (message_id) REFERENCES messages(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_message_attachments_type
    CHECK (attachment_type IN ('IMAGE', 'VIDEO', 'PRODUCT', 'ORDER', 'LOGISTICS', 'COUPON', 'FILE'))
) ENGINE=InnoDB COMMENT='Message images, videos and product/order/logistics cards';

CREATE TABLE IF NOT EXISTS conversation_status_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  from_status VARCHAR(30) NULL,
  to_status VARCHAR(30) NOT NULL,
  changed_by_type VARCHAR(20) NOT NULL,
  changed_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  reason_code VARCHAR(100) NULL,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_conversation_status_history_time (conversation_id, occurred_at),
  KEY idx_conversation_status_history_tenant (tenant_id, occurred_at),
  CONSTRAINT fk_conversation_status_history_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_conversation_status_history_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_conversation_status_history_user
    FOREIGN KEY (changed_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_conversation_status_history_actor
    CHECK (changed_by_type IN ('STAFF', 'AI', 'SYSTEM'))
) ENGINE=InnoDB COMMENT='Append-only Unified Inbox status transitions';

CREATE TABLE IF NOT EXISTS message_templates (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  template_code VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  template_name VARCHAR(255) NOT NULL,
  template_type VARCHAR(20) NOT NULL DEFAULT 'TEMPLATE',
  category VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  valid_from DATETIME(3) NULL,
  valid_until DATETIME(3) NULL,
  current_version_number INT UNSIGNED NULL,
  created_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_message_templates_tenant_code (tenant_id, template_code),
  KEY idx_message_templates_active
    (tenant_id, status, valid_from, valid_until),
  CONSTRAINT fk_message_templates_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_message_templates_creator
    FOREIGN KEY (created_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_message_templates_type
    CHECK (template_type IN ('TEMPLATE', 'MACRO')),
  CONSTRAINT ck_message_templates_status
    CHECK (status IN ('DRAFT', 'ACTIVE', 'EXPIRED', 'DISABLED')),
  CONSTRAINT ck_message_templates_dates
    CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from)
) ENGINE=InnoDB COMMENT='Tenant-specific templates/macros; application may use only active rows inside validity window';

CREATE TABLE IF NOT EXISTS message_template_versions (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  message_template_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  version_number INT UNSIGNED NOT NULL,
  content_text MEDIUMTEXT NOT NULL,
  variables_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
  actions_json JSON NOT NULL DEFAULT (JSON_ARRAY())
    COMMENT 'Optional macro actions such as status/assignee changes',
  created_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  change_note TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_message_template_versions_number
    (message_template_id, version_number),
  CONSTRAINT fk_message_template_versions_template
    FOREIGN KEY (message_template_id) REFERENCES message_templates(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_message_template_versions_user
    FOREIGN KEY (created_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_message_template_versions_number CHECK (version_number > 0)
) ENGINE=InnoDB COMMENT='Immutable template/macro content versions';

CREATE TABLE IF NOT EXISTS conversation_template_usages (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  message_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  template_version_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  used_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  rendered_content MEDIUMTEXT NOT NULL,
  variables_used_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_conversation_template_usages_conversation
    (conversation_id, created_at),
  CONSTRAINT fk_conversation_template_usages_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_conversation_template_usages_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_conversation_template_usages_message
    FOREIGN KEY (message_id) REFERENCES messages(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT fk_conversation_template_usages_version
    FOREIGN KEY (template_version_id) REFERENCES message_template_versions(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_conversation_template_usages_user
    FOREIGN KEY (used_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Audit of exact macro/template version and rendered text used';

CREATE OR REPLACE VIEW active_message_templates AS
SELECT
  mt.id,
  mt.tenant_id,
  mt.template_code,
  mt.template_name,
  mt.template_type,
  mt.category,
  mt.current_version_number,
  mt.valid_from,
  mt.valid_until
FROM message_templates mt
WHERE mt.status = 'ACTIVE'
  AND mt.deleted_at IS NULL
  AND (mt.valid_from IS NULL OR mt.valid_from <= UTC_TIMESTAMP(3))
  AND (mt.valid_until IS NULL OR mt.valid_until > UTC_TIMESTAMP(3));

-- ============================================================================
-- 7. Reliable marketplace integration, synchronization and retry
-- ============================================================================

CREATE TABLE IF NOT EXISTS webhook_inbox (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  external_event_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  signature_valid TINYINT(1) NOT NULL,
  headers_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  payload_json JSON NOT NULL,
  processing_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  locked_by VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
  locked_until DATETIME(3) NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  processed_at DATETIME(3) NULL,
  last_error TEXT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_webhook_inbox_event
    (marketplace_account_id, external_event_id),
  KEY idx_webhook_inbox_worker
    (processing_status, next_retry_at, locked_until, received_at),
  KEY idx_webhook_inbox_tenant_time (tenant_id, received_at DESC),
  CONSTRAINT fk_webhook_inbox_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_webhook_inbox_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_webhook_inbox_signature CHECK (signature_valid IN (0, 1)),
  CONSTRAINT ck_webhook_inbox_status
    CHECK (processing_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD'))
) ENGINE=InnoDB COMMENT='Durable, deduplicated TikTok webhook and Lazada push inbox';

CREATE TABLE IF NOT EXISTS inbound_processing_attempts (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  webhook_inbox_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  attempt_number INT UNSIGNED NOT NULL,
  worker_id VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(100) NULL,
  error_message TEXT NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_inbound_processing_attempts_number
    (webhook_inbox_id, attempt_number),
  CONSTRAINT fk_inbound_processing_attempts_inbox
    FOREIGN KEY (webhook_inbox_id) REFERENCES webhook_inbox(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_inbound_processing_attempts_number CHECK (attempt_number > 0),
  CONSTRAINT ck_inbound_processing_attempts_status
    CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB COMMENT='Every attempt to normalize one inbound marketplace event';

CREATE TABLE IF NOT EXISTS sync_checkpoints (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  resource_type VARCHAR(30) NOT NULL,
  cursor_value TEXT NULL,
  watermark_time DATETIME(3) NULL,
  last_success_at DATETIME(3) NULL,
  last_attempt_at DATETIME(3) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'IDLE',
  last_error TEXT NULL,
  locked_by VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
  locked_until DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 1,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_sync_checkpoints_resource
    (marketplace_account_id, resource_type),
  KEY idx_sync_checkpoints_worker (status, locked_until, last_attempt_at),
  CONSTRAINT fk_sync_checkpoints_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_sync_checkpoints_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_sync_checkpoints_resource
    CHECK (resource_type IN ('PRODUCT', 'ORDER', 'CONVERSATION', 'MESSAGE', 'CUSTOMER', 'BEHAVIOR')),
  CONSTRAINT ck_sync_checkpoints_status
    CHECK (status IN ('IDLE', 'RUNNING', 'ERROR', 'DISABLED')),
  CONSTRAINT ck_sync_checkpoints_version CHECK (version > 0)
) ENGINE=InnoDB COMMENT='Cursor/watermark for initial sync and incremental reconciliation';

CREATE TABLE IF NOT EXISTS outbound_operations (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  operation_type VARCHAR(50) NOT NULL,
  entity_type VARCHAR(30) NOT NULL,
  entity_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  requested_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  idempotency_key VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  target_count INT UNSIGNED NOT NULL DEFAULT 0,
  succeeded_count INT UNSIGNED NOT NULL DEFAULT 0,
  failed_count INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_outbound_operations_id_tenant (id, tenant_id),
  UNIQUE KEY uq_outbound_operations_idempotency (tenant_id, idempotency_key),
  KEY idx_outbound_operations_worker (status, created_at),
  KEY idx_outbound_operations_entity (tenant_id, entity_type, entity_id),
  CONSTRAINT fk_outbound_operations_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_outbound_operations_user
    FOREIGN KEY (requested_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_outbound_operations_type CHECK (
    operation_type IN (
      'CREATE_PRODUCT', 'UPDATE_PRODUCT', 'DELETE_PRODUCT', 'UPDATE_PRICE',
      'UPDATE_INVENTORY', 'CREATE_ORDER', 'UPDATE_ORDER', 'DELETE_ORDER',
      'CONFIRM_ORDER', 'PACK_ORDER', 'READY_TO_SHIP', 'CANCEL_ORDER',
      'REJECT_CANCELLATION', 'REPLY_MESSAGE', 'MARK_MESSAGE_READ'
    )
  ),
  CONSTRAINT ck_outbound_operations_entity
    CHECK (entity_type IN ('PRODUCT', 'VARIANT', 'ORDER', 'MESSAGE', 'CONVERSATION')),
  CONSTRAINT ck_outbound_operations_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'PARTIAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_outbound_operations_counts CHECK (
    succeeded_count + failed_count <= target_count
  )
) ENGINE=InnoDB COMMENT='One canonical user/system operation that may fan out to multiple marketplaces';

CREATE TABLE IF NOT EXISTS outbound_operation_targets (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  outbound_operation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  target_external_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  adapter_operation VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  target_payload_json JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  locked_by VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
  locked_until DATETIME(3) NULL,
  external_request_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  external_result_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  last_error_code VARCHAR(100) NULL,
  last_error_message TEXT NULL,
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_outbound_operation_targets_id_tenant (id, tenant_id),
  UNIQUE KEY uq_outbound_operation_targets_account
    (outbound_operation_id, marketplace_account_id),
  KEY idx_outbound_operation_targets_worker
    (status, next_retry_at, locked_until, created_at),
  CONSTRAINT fk_outbound_operation_targets_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_outbound_operation_targets_operation_tenant
    FOREIGN KEY (outbound_operation_id, tenant_id)
    REFERENCES outbound_operations(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_outbound_operation_targets_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_outbound_operation_targets_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'RETRYING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_outbound_operation_targets_completed CHECK (
    status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR completed_at IS NOT NULL
  )
) ENGINE=InnoDB COMMENT='Marketplace-specific target; one marketplace can retry without rolling back another';

CREATE TABLE IF NOT EXISTS outbound_attempts (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  outbound_target_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  attempt_number INT UNSIGNED NOT NULL,
  request_id VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  request_method VARCHAR(10) NOT NULL,
  request_path VARCHAR(500) NOT NULL,
  request_body_json JSON NULL,
  response_http_status SMALLINT UNSIGNED NULL,
  response_code VARCHAR(100) NULL,
  response_body_json JSON NULL,
  latency_ms INT UNSIGNED NULL,
  error_class VARCHAR(100) NULL,
  is_retryable TINYINT(1) NOT NULL DEFAULT 0,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_outbound_attempts_number (outbound_target_id, attempt_number),
  UNIQUE KEY uq_outbound_attempts_request (request_id),
  CONSTRAINT fk_outbound_attempts_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_outbound_attempts_target_tenant
    FOREIGN KEY (outbound_target_id, tenant_id)
    REFERENCES outbound_operation_targets(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_outbound_attempts_number CHECK (attempt_number > 0),
  CONSTRAINT ck_outbound_attempts_method
    CHECK (request_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
  CONSTRAINT ck_outbound_attempts_http
    CHECK (response_http_status IS NULL OR response_http_status BETWEEN 100 AND 599),
  CONSTRAINT ck_outbound_attempts_retryable CHECK (is_retryable IN (0, 1))
) ENGINE=InnoDB COMMENT='Sanitized request/response audit for each outbound marketplace call';

CREATE TABLE IF NOT EXISTS idempotency_records (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  scope_code VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  idempotency_key VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  request_hash CHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  processing_status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
  response_http_status SMALLINT UNSIGNED NULL,
  response_json JSON NULL,
  locked_by VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
  locked_until DATETIME(3) NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_idempotency_records_scope
    (tenant_id, scope_code, idempotency_key),
  KEY idx_idempotency_records_expiry (expires_at),
  CONSTRAINT fk_idempotency_records_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_idempotency_records_account_tenant
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_idempotency_records_status
    CHECK (processing_status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB COMMENT='Prevents duplicate product/order/message mutations during client or worker retries';

-- ============================================================================
-- 8. AI Sale: RAG, understanding, generation, tools and quality gate
-- ============================================================================

CREATE TABLE IF NOT EXISTS knowledge_bases (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  knowledge_base_name VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'BUILDING',
  embedding_model VARCHAR(100) NOT NULL,
  vector_store_provider VARCHAR(50) NOT NULL DEFAULT 'EXTERNAL',
  vector_namespace VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
  settings_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_knowledge_bases_id_tenant (id, tenant_id),
  UNIQUE KEY uq_knowledge_bases_tenant_name (tenant_id, knowledge_base_name),
  UNIQUE KEY uq_knowledge_bases_namespace (vector_namespace),
  CONSTRAINT fk_knowledge_bases_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_knowledge_bases_status
    CHECK (status IN ('ACTIVE', 'BUILDING', 'ERROR', 'DISABLED'))
) ENGINE=InnoDB COMMENT='Tenant-isolated RAG knowledge base; embeddings may live in a vector store';

CREATE TABLE IF NOT EXISTS knowledge_documents (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  knowledge_base_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  source_type VARCHAR(30) NOT NULL,
  source_reference VARCHAR(500) NULL,
  title VARCHAR(500) NOT NULL,
  content_hash CHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  metadata_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  indexed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_knowledge_documents_id_tenant (id, tenant_id),
  UNIQUE KEY uq_knowledge_documents_hash (knowledge_base_id, content_hash),
  KEY idx_knowledge_documents_status (knowledge_base_id, status),
  CONSTRAINT fk_knowledge_documents_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_knowledge_documents_base_tenant
    FOREIGN KEY (knowledge_base_id, tenant_id)
    REFERENCES knowledge_bases(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_knowledge_documents_source
    CHECK (source_type IN ('FILE', 'URL', 'TEXT', 'PRODUCT', 'POLICY', 'FAQ', 'SHIPPING')),
  CONSTRAINT ck_knowledge_documents_status
    CHECK (status IN ('PENDING', 'INDEXING', 'INDEXED', 'ERROR', 'ARCHIVED'))
) ENGINE=InnoDB COMMENT='Shop policies, FAQ, catalog and other RAG sources';

CREATE TABLE IF NOT EXISTS knowledge_chunks (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  knowledge_document_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  chunk_index INT UNSIGNED NOT NULL,
  content_text MEDIUMTEXT NOT NULL,
  token_count INT UNSIGNED NOT NULL DEFAULT 0,
  embedding_reference VARCHAR(500) COLLATE utf8mb4_0900_bin NULL,
  metadata_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_knowledge_chunks_id_tenant (id, tenant_id),
  UNIQUE KEY uq_knowledge_chunks_index
    (knowledge_document_id, chunk_index),
  KEY idx_knowledge_chunks_tenant (tenant_id, knowledge_document_id),
  CONSTRAINT fk_knowledge_chunks_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_knowledge_chunks_document_tenant
    FOREIGN KEY (knowledge_document_id, tenant_id)
    REFERENCES knowledge_documents(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='RAG retrieval units and references to their vector embeddings';

CREATE TABLE IF NOT EXISTS ai_language_analyses (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  message_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  detected_language VARCHAR(20) NOT NULL,
  intent_code VARCHAR(100) NOT NULL,
  sentiment VARCHAR(30) NULL,
  urgency VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  entities_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
  confidence DECIMAL(5,4) NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ai_language_analyses_message (message_id),
  KEY idx_ai_language_analyses_intent
    (tenant_id, intent_code, created_at),
  CONSTRAINT fk_ai_language_analyses_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_language_analyses_conversation_tenant
    FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversations(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_language_analyses_message_tenant
    FOREIGN KEY (message_id, tenant_id) REFERENCES messages(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_ai_language_analyses_urgency
    CHECK (urgency IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
  CONSTRAINT ck_ai_language_analyses_confidence
    CHECK (confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB COMMENT='Language, intent, sentiment, urgency and entities extracted from a customer message';

CREATE TABLE IF NOT EXISTS ai_conversation_memories (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  memory_type VARCHAR(30) NOT NULL,
  content_text TEXT NOT NULL,
  source_message_ids_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
  confidence DECIMAL(5,4) NOT NULL,
  expires_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ai_conversation_memories_active
    (conversation_id, memory_type, expires_at),
  CONSTRAINT fk_ai_conversation_memories_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_conversation_memories_conversation_tenant
    FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversations(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_ai_conversation_memories_type
    CHECK (memory_type IN ('SUMMARY', 'FACT', 'PREFERENCE', 'OPEN_ISSUE', 'POST_PURCHASE')),
  CONSTRAINT ck_ai_conversation_memories_confidence
    CHECK (confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB COMMENT='Memory is conversation-scoped and therefore never merges same-name buyers across marketplaces';

CREATE TABLE IF NOT EXISTS ai_response_runs (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  trigger_message_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  output_message_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  idempotency_key VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
  provider VARCHAR(50) NOT NULL DEFAULT 'GOOGLE',
  provider_request_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  model_name VARCHAR(100) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  prompt_version VARCHAR(100) NOT NULL,
  generated_text MEDIUMTEXT NULL,
  result_json JSON NULL,
  safety_flags_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
  requires_human_review TINYINT(1) NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL DEFAULT 'GENERATED',
  confidence DECIMAL(5,4) NULL,
  latency_ms INT UNSIGNED NULL,
  input_tokens INT UNSIGNED NULL,
  output_tokens INT UNSIGNED NULL,
  cached_tokens INT UNSIGNED NULL,
  estimated_cost_usd DECIMAL(18,8) NULL,
  token_usage_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  error_code VARCHAR(100) NULL,
  failure_reason TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_ai_response_runs_id_tenant (id, tenant_id),
  UNIQUE KEY uq_ai_response_runs_idempotency (tenant_id, idempotency_key),
  KEY idx_ai_response_runs_conversation_time
    (conversation_id, created_at DESC),
  KEY idx_ai_response_runs_quality_queue
    (tenant_id, status, created_at),
  CONSTRAINT fk_ai_response_runs_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_response_runs_conversation_tenant
    FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversations(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_response_runs_trigger_tenant
    FOREIGN KEY (trigger_message_id, tenant_id) REFERENCES messages(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_ai_response_runs_output_tenant
    FOREIGN KEY (output_message_id, tenant_id) REFERENCES messages(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_ai_response_runs_status
    CHECK (status IN ('GENERATING', 'GENERATED', 'QUALITY_CHECKING', 'APPROVED', 'REJECTED', 'SENT', 'FAILED', 'HANDED_OFF')),
  CONSTRAINT ck_ai_response_runs_confidence
    CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
  CONSTRAINT ck_ai_response_runs_human_review
    CHECK (requires_human_review IN (0, 1)),
  CONSTRAINT ck_ai_response_runs_generated_text CHECK (
    status IN ('GENERATING', 'FAILED')
    OR generated_text IS NOT NULL
  ),
  CONSTRAINT ck_ai_response_runs_cost
    CHECK (estimated_cost_usd IS NULL OR estimated_cost_usd >= 0)
) ENGINE=InnoDB COMMENT='Natural-language response generation lifecycle before and after the send quality gate';

CREATE TABLE IF NOT EXISTS ai_response_sources (
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  ai_response_run_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  knowledge_chunk_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  rank_number INT UNSIGNED NOT NULL,
  relevance_score DECIMAL(7,6) NOT NULL,
  excerpt_text TEXT NULL,
  PRIMARY KEY (ai_response_run_id, knowledge_chunk_id),
  UNIQUE KEY uq_ai_response_sources_rank (ai_response_run_id, rank_number),
  CONSTRAINT fk_ai_response_sources_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_response_sources_run_tenant
    FOREIGN KEY (ai_response_run_id, tenant_id)
    REFERENCES ai_response_runs(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_response_sources_chunk_tenant
    FOREIGN KEY (knowledge_chunk_id, tenant_id)
    REFERENCES knowledge_chunks(id, tenant_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_ai_response_sources_rank CHECK (rank_number > 0),
  CONSTRAINT ck_ai_response_sources_score CHECK (relevance_score BETWEEN 0 AND 1)
) ENGINE=InnoDB COMMENT='Exact RAG chunks used to support an AI answer';

CREATE TABLE IF NOT EXISTS ai_tool_calls (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  ai_response_run_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tool_name VARCHAR(100) NOT NULL,
  input_json JSON NOT NULL,
  output_json JSON NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
  error_code VARCHAR(100) NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_ai_tool_calls_run (ai_response_run_id, started_at),
  CONSTRAINT fk_ai_tool_calls_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_tool_calls_run
    FOREIGN KEY (ai_response_run_id) REFERENCES ai_response_runs(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_ai_tool_calls_tool CHECK (
    tool_name IN (
      'ORDER_LOOKUP', 'SHIPMENT_LOOKUP', 'PRODUCT_LOOKUP',
      'INVENTORY_LOOKUP', 'CUSTOMER_HISTORY', 'CREATE_CARE_TASK'
    )
  ),
  CONSTRAINT ck_ai_tool_calls_status
    CHECK (status IN ('REQUESTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DENIED'))
) ENGINE=InnoDB COMMENT='Audited AI order, tracking, product and customer-history lookups';

CREATE TABLE IF NOT EXISTS ai_quality_checks (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  ai_response_run_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  check_type VARCHAR(50) NOT NULL,
  passed TINYINT(1) NOT NULL,
  score DECIMAL(5,4) NULL,
  findings_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  checker_version VARCHAR(100) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ai_quality_checks_type (ai_response_run_id, check_type),
  KEY idx_ai_quality_checks_failed (tenant_id, passed, created_at),
  CONSTRAINT fk_ai_quality_checks_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_quality_checks_run
    FOREIGN KEY (ai_response_run_id) REFERENCES ai_response_runs(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_ai_quality_checks_type CHECK (
    check_type IN (
      'HALLUCINATION', 'SHOP_POLICY', 'TONE', 'PII', 'SAFETY',
      'LANGUAGE', 'ORDER_FACTS', 'DUPLICATE', 'OVERALL'
    )
  ),
  CONSTRAINT ck_ai_quality_checks_passed CHECK (passed IN (0, 1)),
  CONSTRAINT ck_ai_quality_checks_score
    CHECK (score IS NULL OR score BETWEEN 0 AND 1)
) ENGINE=InnoDB COMMENT='Pre-send quality controls; all required checks must pass before automatic send';

CREATE TABLE IF NOT EXISTS ai_recommendations (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  recommendation_type VARCHAR(50) NOT NULL,
  target_entity_type VARCHAR(30) NULL,
  target_entity_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  recommendation_json JSON NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
  model_version VARCHAR(100) NOT NULL,
  decided_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NULL,
  decided_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_ai_recommendations_queue
    (tenant_id, status, created_at),
  KEY idx_ai_recommendations_conversation
    (conversation_id, created_at DESC),
  CONSTRAINT fk_ai_recommendations_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_recommendations_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_recommendations_customer
    FOREIGN KEY (marketplace_customer_id) REFERENCES marketplace_customers(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT fk_ai_recommendations_user
    FOREIGN KEY (decided_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_ai_recommendations_type CHECK (
    recommendation_type IN (
      'ANSWER', 'PRODUCT', 'VOUCHER', 'RESOLUTION', 'REFUND',
      'FOLLOW_UP', 'HUMAN_HANDOFF'
    )
  ),
  CONSTRAINT ck_ai_recommendations_confidence
    CHECK (confidence BETWEEN 0 AND 1),
  CONSTRAINT ck_ai_recommendations_status
    CHECK (status IN ('PROPOSED', 'ACCEPTED', 'REJECTED', 'EXECUTED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='AI-proposed solutions; important actions remain subject to policy or human approval';

-- ============================================================================
-- 9. Human handoff, notifications, post-purchase care and AI feedback
-- ============================================================================

CREATE TABLE IF NOT EXISTS human_handoffs (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  ai_response_run_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  reason_code VARCHAR(100) NOT NULL,
  reason_text TEXT NULL,
  priority VARCHAR(20) NOT NULL DEFAULT 'HIGH',
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
  assigned_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  accepted_at DATETIME(3) NULL,
  resolved_at DATETIME(3) NULL,
  resolution_note TEXT NULL,
  PRIMARY KEY (id),
  KEY idx_human_handoffs_queue
    (tenant_id, status, priority, requested_at),
  KEY idx_human_handoffs_conversation
    (conversation_id, requested_at DESC),
  CONSTRAINT fk_human_handoffs_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_human_handoffs_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_human_handoffs_ai_run
    FOREIGN KEY (ai_response_run_id) REFERENCES ai_response_runs(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT fk_human_handoffs_assignee
    FOREIGN KEY (assigned_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_human_handoffs_priority
    CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
  CONSTRAINT ck_human_handoffs_status
    CHECK (status IN ('REQUESTED', 'NOTIFIED', 'ACCEPTED', 'RESOLVED', 'CANCELLED'))
) ENGINE=InnoDB COMMENT='AI-to-CSKH transfer lifecycle';

CREATE TABLE IF NOT EXISTS notifications (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  recipient_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  notification_type VARCHAR(50) NOT NULL,
  channel VARCHAR(20) NOT NULL,
  title VARCHAR(255) NOT NULL,
  body_text TEXT NOT NULL,
  reference_type VARCHAR(50) NULL,
  reference_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
  scheduled_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at DATETIME(3) NULL,
  read_at DATETIME(3) NULL,
  failed_at DATETIME(3) NULL,
  error_message TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_notifications_delivery (status, scheduled_at),
  KEY idx_notifications_user (recipient_user_id, read_at, created_at DESC),
  CONSTRAINT fk_notifications_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_notifications_recipient
    FOREIGN KEY (recipient_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_notifications_channel
    CHECK (channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH')),
  CONSTRAINT ck_notifications_status
    CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'FAILED', 'READ', 'CANCELLED'))
) ENGINE=InnoDB COMMENT='In-app/email/web notifications, including urgent CSKH handoff alerts';

CREATE TABLE IF NOT EXISTS post_purchase_care_tasks (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  order_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  care_type VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
  scheduled_at DATETIME(3) NOT NULL,
  due_at DATETIME(3) NULL,
  assigned_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  ai_generated_message TEXT NULL,
  outcome_code VARCHAR(100) NULL,
  outcome_note TEXT NULL,
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_post_purchase_care_worker
    (tenant_id, status, scheduled_at),
  KEY idx_post_purchase_care_customer
    (marketplace_customer_id, created_at DESC),
  CONSTRAINT fk_post_purchase_care_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_post_purchase_care_order
    FOREIGN KEY (order_id) REFERENCES orders(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_post_purchase_care_customer
    FOREIGN KEY (marketplace_customer_id) REFERENCES marketplace_customers(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_post_purchase_care_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT fk_post_purchase_care_assignee
    FOREIGN KEY (assigned_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_post_purchase_care_type CHECK (
    care_type IN (
      'DELIVERY_CONFIRMATION', 'USAGE_GUIDANCE', 'SATISFACTION_CHECK',
      'REVIEW_REQUEST', 'REORDER_REMINDER', 'ISSUE_FOLLOW_UP'
    )
  ),
  CONSTRAINT ck_post_purchase_care_status
    CHECK (status IN ('SCHEDULED', 'READY', 'IN_PROGRESS', 'COMPLETED', 'SKIPPED', 'FAILED'))
) ENGINE=InnoDB COMMENT='AI-assisted after-sales follow-up tied to the actual buyer identity and order';

CREATE TABLE IF NOT EXISTS ai_feedback (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  ai_response_run_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  feedback_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  rating SMALLINT UNSIGNED NOT NULL,
  feedback_type VARCHAR(50) NOT NULL,
  comment_text TEXT NULL,
  corrected_text MEDIUMTEXT NULL,
  review_status VARCHAR(20) NOT NULL DEFAULT 'NEW',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_ai_feedback_learning_queue
    (tenant_id, review_status, created_at),
  KEY idx_ai_feedback_run (ai_response_run_id),
  CONSTRAINT fk_ai_feedback_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_feedback_run
    FOREIGN KEY (ai_response_run_id) REFERENCES ai_response_runs(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_feedback_user
    FOREIGN KEY (feedback_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_ai_feedback_rating CHECK (rating BETWEEN 1 AND 5),
  CONSTRAINT ck_ai_feedback_type CHECK (
    feedback_type IN (
      'GOOD', 'INCORRECT', 'UNNATURAL', 'MISSING_CONTEXT',
      'POLICY', 'TONE', 'HALLUCINATION', 'OTHER'
    )
  ),
  CONSTRAINT ck_ai_feedback_status
    CHECK (review_status IN ('NEW', 'REVIEWED', 'APPROVED_FOR_LEARNING', 'REJECTED', 'APPLIED'))
) ENGINE=InnoDB COMMENT='CSKH ratings/corrections; feedback is reviewed before use for AI improvement';

CREATE TABLE IF NOT EXISTS ai_learning_examples (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  ai_feedback_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  input_context_json JSON NOT NULL,
  preferred_output MEDIUMTEXT NOT NULL,
  rejected_output MEDIUMTEXT NULL,
  dataset_split VARCHAR(20) NOT NULL DEFAULT 'TRAIN',
  status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  applied_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_ai_learning_examples_feedback (ai_feedback_id),
  KEY idx_ai_learning_examples_dataset (tenant_id, status, dataset_split),
  CONSTRAINT fk_ai_learning_examples_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_learning_examples_feedback
    FOREIGN KEY (ai_feedback_id) REFERENCES ai_feedback(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_ai_learning_examples_split
    CHECK (dataset_split IN ('TRAIN', 'VALIDATION', 'TEST')),
  CONSTRAINT ck_ai_learning_examples_status
    CHECK (status IN ('APPROVED', 'EXPORTED', 'APPLIED', 'RETIRED'))
) ENGINE=InnoDB COMMENT='Human-approved learning examples; avoids uncontrolled self-learning from raw feedback';

-- ============================================================================
-- 10. AI customer profiles, product interests and multi-label segmentation
-- ============================================================================

CREATE TABLE IF NOT EXISTS customer_ai_profiles (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  verified_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  profile_summary TEXT NULL,
  features_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  order_count INT UNSIGNED NOT NULL DEFAULT 0,
  conversation_count INT UNSIGNED NOT NULL DEFAULT 0,
  lifetime_value DECIMAL(18,2) NOT NULL DEFAULT 0,
  model_version VARCHAR(100) NOT NULL,
  last_computed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_customer_ai_profiles_marketplace_customer
    (marketplace_customer_id),
  KEY idx_customer_ai_profiles_tenant_value
    (tenant_id, lifetime_value DESC),
  CONSTRAINT fk_customer_ai_profiles_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_customer_ai_profiles_marketplace_customer
    FOREIGN KEY (marketplace_customer_id) REFERENCES marketplace_customers(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_customer_ai_profiles_verified_customer
    FOREIGN KEY (verified_customer_id) REFERENCES customers(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_customer_ai_profiles_values
    CHECK (lifetime_value >= 0)
) ENGINE=InnoDB COMMENT='Marketplace-buyer profile; remains separate until an explicit verified identity link exists';

CREATE TABLE IF NOT EXISTS customer_product_interests (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  marketplace_customer_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  external_product_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  interest_type VARCHAR(30) NOT NULL,
  score DECIMAL(5,4) NOT NULL,
  evidence_count INT UNSIGNED NOT NULL DEFAULT 1,
  evidence_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
  first_observed_at DATETIME(3) NOT NULL,
  last_observed_at DATETIME(3) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_customer_product_interests_internal
    (marketplace_customer_id, product_id, interest_type),
  KEY idx_customer_product_interests_external
    (marketplace_customer_id, external_product_id, interest_type),
  KEY idx_customer_product_interests_rank
    (tenant_id, marketplace_customer_id, score DESC),
  CONSTRAINT fk_customer_product_interests_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_customer_product_interests_customer
    FOREIGN KEY (marketplace_customer_id) REFERENCES marketplace_customers(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_customer_product_interests_product
    FOREIGN KEY (product_id) REFERENCES products(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_customer_product_interests_identity
    CHECK (product_id IS NOT NULL OR external_product_id IS NOT NULL),
  CONSTRAINT ck_customer_product_interests_type
    CHECK (interest_type IN ('VIEWED', 'CHATTED', 'CARTED', 'PURCHASED', 'REORDERED', 'DISLIKED')),
  CONSTRAINT ck_customer_product_interests_score CHECK (score BETWEEN 0 AND 1)
) ENGINE=InnoDB COMMENT='Explainable product interests derived from views, chat and order history';

CREATE TABLE IF NOT EXISTS customer_segment_definitions (
  segment_code VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  segment_name_vi VARCHAR(255) NOT NULL,
  description TEXT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (segment_code),
  CONSTRAINT ck_customer_segment_definitions_code CHECK (
    segment_code IN (
      'NEW', 'FIRST_TIME_BUYER', 'LOYAL', 'LIKELY_TO_REPURCHASE',
      'LIKELY_TO_CONVERT', 'CHURN_RISK', 'PRODUCT_DISSATISFIED'
    )
  ),
  CONSTRAINT ck_customer_segment_definitions_active CHECK (is_active IN (0, 1))
) ENGINE=InnoDB COMMENT='Required customer segments; assignments are multi-label and time-bounded';

CREATE TABLE IF NOT EXISTS customer_segment_assignments (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  customer_ai_profile_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  segment_code VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  score DECIMAL(5,4) NOT NULL,
  reason_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  assignment_source VARCHAR(20) NOT NULL DEFAULT 'AI',
  model_version VARCHAR(100) NULL,
  valid_from DATETIME(3) NOT NULL,
  valid_until DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_customer_segment_assignments_version
    (customer_ai_profile_id, segment_code, valid_from),
  KEY idx_customer_segment_assignments_active
    (tenant_id, segment_code, valid_until, score DESC),
  CONSTRAINT fk_customer_segment_assignments_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_customer_segment_assignments_profile
    FOREIGN KEY (customer_ai_profile_id) REFERENCES customer_ai_profiles(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_customer_segment_assignments_definition
    FOREIGN KEY (segment_code) REFERENCES customer_segment_definitions(segment_code)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_customer_segment_assignments_score CHECK (score BETWEEN 0 AND 1),
  CONSTRAINT ck_customer_segment_assignments_source
    CHECK (assignment_source IN ('AI', 'RULE', 'MANUAL')),
  CONSTRAINT ck_customer_segment_assignments_dates
    CHECK (valid_until IS NULL OR valid_until > valid_from)
) ENGINE=InnoDB COMMENT='Segment history for new, first buyer, loyal, repurchase, conversion, churn and dissatisfaction';

-- ============================================================================
-- 11. Analytics, product quality alerts and scheduled email reporting
-- ============================================================================

CREATE TABLE IF NOT EXISTS analytics_daily_sales (
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  metric_date DATE NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  currency CHAR(3) NOT NULL,
  order_count INT UNSIGNED NOT NULL DEFAULT 0,
  delivered_order_count INT UNSIGNED NOT NULL DEFAULT 0,
  item_quantity INT UNSIGNED NOT NULL DEFAULT 0,
  gross_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
  discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  shipping_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
  refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  net_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
  computed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, metric_date, marketplace_account_id, currency),
  KEY idx_analytics_daily_sales_account
    (marketplace_account_id, metric_date),
  CONSTRAINT fk_analytics_daily_sales_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_analytics_daily_sales_account
    FOREIGN KEY (marketplace_account_id) REFERENCES marketplace_accounts(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_analytics_daily_sales_values CHECK (
    gross_revenue >= 0 AND discount_amount >= 0 AND shipping_revenue >= 0
    AND refund_amount >= 0
  )
) ENGINE=InnoDB COMMENT='Daily marketplace revenue facts; sum by ISO week, month or year';

CREATE TABLE IF NOT EXISTS analytics_daily_product_performance (
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  metric_date DATE NOT NULL,
  marketplace_account_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  product_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  currency CHAR(3) NOT NULL,
  order_count INT UNSIGNED NOT NULL DEFAULT 0,
  purchased_quantity INT UNSIGNED NOT NULL DEFAULT 0,
  gross_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
  cancelled_order_count INT UNSIGNED NOT NULL DEFAULT 0,
  returned_order_count INT UNSIGNED NOT NULL DEFAULT 0,
  failed_order_count INT UNSIGNED NOT NULL DEFAULT 0,
  cancel_rate DECIMAL(7,6) NOT NULL DEFAULT 0,
  return_rate DECIMAL(7,6) NOT NULL DEFAULT 0,
  computed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (
    tenant_id, metric_date, marketplace_account_id, product_id, currency
  ),
  KEY idx_analytics_product_ranking
    (tenant_id, metric_date, purchased_quantity DESC),
  KEY idx_analytics_product_quality
    (tenant_id, metric_date, cancel_rate DESC, return_rate DESC),
  CONSTRAINT fk_analytics_product_performance_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_analytics_product_performance_account
    FOREIGN KEY (marketplace_account_id) REFERENCES marketplace_accounts(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_analytics_product_performance_product
    FOREIGN KEY (product_id) REFERENCES products(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_analytics_product_performance_values CHECK (
    gross_revenue >= 0
    AND cancel_rate BETWEEN 0 AND 1
    AND return_rate BETWEEN 0 AND 1
  )
) ENGINE=InnoDB COMMENT='Daily product facts for best/worst sellers and cancel/return trend detection';

CREATE TABLE IF NOT EXISTS analytics_alert_rules (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  rule_code VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  alert_type VARCHAR(50) NOT NULL,
  lookback_days SMALLINT UNSIGNED NOT NULL DEFAULT 7,
  minimum_order_count INT UNSIGNED NOT NULL DEFAULT 5,
  threshold_value DECIMAL(18,6) NOT NULL,
  comparison_operator VARCHAR(10) NOT NULL DEFAULT 'GTE',
  severity VARCHAR(20) NOT NULL DEFAULT 'WARNING',
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  recipient_role_codes_json JSON NOT NULL DEFAULT (JSON_ARRAY('TENANT_MANAGER')),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_analytics_alert_rules_code (tenant_id, rule_code),
  CONSTRAINT fk_analytics_alert_rules_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_analytics_alert_rules_type
    CHECK (alert_type IN ('HIGH_CANCEL_RATE', 'HIGH_RETURN_RATE', 'CANCEL_RATE_SPIKE', 'RETURN_RATE_SPIKE')),
  CONSTRAINT ck_analytics_alert_rules_operator
    CHECK (comparison_operator IN ('GT', 'GTE', 'LT', 'LTE')),
  CONSTRAINT ck_analytics_alert_rules_severity
    CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  CONSTRAINT ck_analytics_alert_rules_active CHECK (is_active IN (0, 1))
) ENGINE=InnoDB COMMENT='Tenant-configurable product cancellation/return alert thresholds';

CREATE TABLE IF NOT EXISTS analytics_alerts (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  alert_rule_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  alert_type VARCHAR(50) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  entity_type VARCHAR(30) NOT NULL,
  entity_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  metric_value DECIMAL(18,6) NULL,
  previous_metric_value DECIMAL(18,6) NULL,
  threshold_value DECIMAL(18,6) NULL,
  details_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  detected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  acknowledged_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  acknowledged_at DATETIME(3) NULL,
  resolved_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_analytics_alerts_queue
    (tenant_id, status, severity, detected_at DESC),
  KEY idx_analytics_alerts_entity
    (tenant_id, entity_type, entity_id, detected_at DESC),
  CONSTRAINT fk_analytics_alerts_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_analytics_alerts_rule
    FOREIGN KEY (alert_rule_id) REFERENCES analytics_alert_rules(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT fk_analytics_alerts_user
    FOREIGN KEY (acknowledged_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_analytics_alerts_type
    CHECK (alert_type IN ('HIGH_CANCEL_RATE', 'HIGH_RETURN_RATE', 'CANCEL_RATE_SPIKE', 'RETURN_RATE_SPIKE')),
  CONSTRAINT ck_analytics_alerts_severity
    CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  CONSTRAINT ck_analytics_alerts_entity
    CHECK (entity_type IN ('PRODUCT', 'MARKETPLACE_ACCOUNT')),
  CONSTRAINT ck_analytics_alerts_status
    CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED'))
) ENGINE=InnoDB COMMENT='Detected product quality alerts for managers';

CREATE TABLE IF NOT EXISTS report_schedules (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  report_type VARCHAR(50) NOT NULL DEFAULT 'DAILY_MANAGEMENT',
  schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  send_time TIME NOT NULL DEFAULT '08:00:00',
  recipient_emails_json JSON NOT NULL,
  include_marketplace_breakdown TINYINT(1) NOT NULL DEFAULT 1,
  include_product_ranking TINYINT(1) NOT NULL DEFAULT 1,
  include_alerts TINYINT(1) NOT NULL DEFAULT 1,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  next_run_at DATETIME(3) NULL,
  created_by_user_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_report_schedules_worker (is_active, next_run_at),
  CONSTRAINT fk_report_schedules_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_report_schedules_user
    FOREIGN KEY (created_by_user_id) REFERENCES tenant_users(id)
    ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_report_schedules_type
    CHECK (report_type IN ('DAILY_MANAGEMENT')),
  CONSTRAINT ck_report_schedules_flags CHECK (
    include_marketplace_breakdown IN (0, 1)
    AND include_product_ranking IN (0, 1)
    AND include_alerts IN (0, 1)
    AND is_active IN (0, 1)
  )
) ENGINE=InnoDB COMMENT='Daily manager email configuration';

CREATE TABLE IF NOT EXISTS report_deliveries (
  id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  report_schedule_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  report_date DATE NOT NULL,
  period_start DATETIME(3) NOT NULL,
  period_end DATETIME(3) NOT NULL,
  recipient_emails_json JSON NOT NULL,
  subject_text VARCHAR(500) NOT NULL,
  report_payload_json JSON NOT NULL,
  attachment_storage_key VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
  provider_message_id VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  locked_by VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
  locked_until DATETIME(3) NULL,
  sent_at DATETIME(3) NULL,
  last_error TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_report_deliveries_schedule_date
    (report_schedule_id, report_date),
  KEY idx_report_deliveries_worker (status, next_retry_at, locked_until, created_at),
  CONSTRAINT fk_report_deliveries_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_report_deliveries_schedule
    FOREIGN KEY (report_schedule_id) REFERENCES report_schedules(id)
    ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_report_deliveries_status
    CHECK (status IN ('QUEUED', 'GENERATING', 'SENDING', 'SENT', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_report_deliveries_period CHECK (period_end > period_start)
) ENGINE=InnoDB COMMENT='Idempotent generation and email-delivery history for daily management reports';

-- ============================================================================
-- 12. Rerun-safe reference/demo seed data
--
-- Passwords/tokens below are placeholders, never plaintext production secrets.
-- Replace the hashes and encrypted values through the application/KMS before use.
-- ============================================================================

INSERT INTO platform_admins (
  id, email, password_hash, password_algorithm, display_name, status
) VALUES (
  '00000000-0000-0000-0000-000000000001',
  'owner@example.test',
  '$argon2id$v=19$m=65536,t=3,p=1$REPLACE_ME$REPLACE_WITH_REAL_HASH',
  'ARGON2ID',
  'Platform Owner',
  'ACTIVE'
) ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  status = VALUES(status);

INSERT INTO subscription_plans (
  id, plan_code, plan_name, billing_period, price_amount, currency,
  limits_json, features_json, status
) VALUES (
  '10000000-0000-0000-0000-000000000001',
  'DEMO',
  'Gói Demo OmnichannelPOS',
  'MONTHLY',
  0,
  'VND',
  JSON_OBJECT('marketplace_accounts', 2, 'tenant_users', 10),
  JSON_OBJECT('ai_sale', TRUE, 'analytics', TRUE, 'daily_email', TRUE),
  'ACTIVE'
) ON DUPLICATE KEY UPDATE
  plan_name = VALUES(plan_name),
  limits_json = VALUES(limits_json),
  features_json = VALUES(features_json),
  status = VALUES(status);

INSERT INTO tenants (
  id, tenant_code, tenant_name, status, timezone_name, default_currency,
  settings_json, provisioned_by_admin_id, provisioned_at
) VALUES (
  '20000000-0000-0000-0000-000000000001',
  'TENANT_DEMO_01',
  'Demo Omnichannel Shop',
  'ACTIVE',
  'Asia/Ho_Chi_Minh',
  'VND',
  JSON_OBJECT('first_login_password_change_required', TRUE),
  '00000000-0000-0000-0000-000000000001',
  '2026-07-27 00:00:00.000'
) ON DUPLICATE KEY UPDATE
  tenant_name = VALUES(tenant_name),
  status = VALUES(status),
  settings_json = VALUES(settings_json);

INSERT INTO tenant_subscriptions (
  id, tenant_id, subscription_plan_id, status, starts_at,
  current_period_ends_at, created_by_admin_id
) VALUES (
  '21000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000001',
  '10000000-0000-0000-0000-000000000001',
  'ACTIVE',
  '2026-07-27 00:00:00.000',
  '2027-07-27 00:00:00.000',
  '00000000-0000-0000-0000-000000000001'
) ON DUPLICATE KEY UPDATE
  status = VALUES(status),
  current_period_ends_at = VALUES(current_period_ends_at);

INSERT INTO data_protection_policies (
  id, tenant_id, data_category, retention_days, encrypt_at_rest,
  redact_in_logs, allow_ai_processing, purge_enabled, policy_version
) VALUES
  ('61000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'CUSTOMER_CONTACT', 730, 1, 1, 0, 1, 'v1'),
  ('61000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', 'ORDER_ADDRESS', 730, 1, 1, 0, 1, 'v1'),
  ('61000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001', 'MESSAGE_CONTENT', 365, 1, 1, 1, 1, 'v1'),
  ('61000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000001', 'RAW_PAYLOAD', 90, 1, 1, 0, 1, 'v1'),
  ('61000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000001', 'WEBHOOK_PAYLOAD', 90, 1, 1, 0, 1, 'v1'),
  ('61000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000001', 'API_AUDIT', 180, 1, 1, 0, 1, 'v1'),
  ('61000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000001', 'AI_CONTEXT', 180, 1, 1, 1, 1, 'v1'),
  ('61000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000001', 'AI_OUTPUT', 365, 1, 1, 1, 1, 'v1')
ON DUPLICATE KEY UPDATE
  retention_days = VALUES(retention_days),
  encrypt_at_rest = VALUES(encrypt_at_rest),
  redact_in_logs = VALUES(redact_in_logs),
  allow_ai_processing = VALUES(allow_ai_processing),
  purge_enabled = VALUES(purge_enabled),
  policy_version = VALUES(policy_version);

INSERT INTO tenant_users (
  id, tenant_id, email, display_name, status, provisioned_by_admin_id
) VALUES (
  '30000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000001',
  'manager@example.test',
  'Quản lý Demo',
  'ACTIVE',
  '00000000-0000-0000-0000-000000000001'
) ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  status = VALUES(status);

INSERT INTO tenant_user_credentials (
  tenant_user_id, password_hash, password_algorithm, must_change_password,
  credential_version
) VALUES (
  '30000000-0000-0000-0000-000000000001',
  '$argon2id$v=19$m=65536,t=3,p=1$REPLACE_ME$TEMPORARY_PASSWORD_HASH',
  'ARGON2ID',
  1,
  1
) ON DUPLICATE KEY UPDATE
  must_change_password = VALUES(must_change_password);

INSERT INTO roles (
  id, tenant_id, role_code, role_name, description, is_system
) VALUES
  (
    '40000000-0000-0000-0000-000000000001',
    NULL,
    'TENANT_MANAGER',
    'Quản lý tenant',
    'Quản lý kết nối, sản phẩm, đơn hàng, CSKH, AI và báo cáo trong tenant',
    1
  ),
  (
    '40000000-0000-0000-0000-000000000002',
    NULL,
    'CS_AGENT',
    'Nhân viên CSKH',
    'Xử lý Unified Inbox, handoff và feedback AI',
    1
  )
ON DUPLICATE KEY UPDATE
  role_name = VALUES(role_name),
  description = VALUES(description);

INSERT INTO permissions (
  id, permission_code, permission_name, resource_code, action_code
) VALUES
  ('41000000-0000-0000-0000-000000000001', 'ACCOUNT.CONNECT', 'Kết nối tài khoản sàn', 'ACCOUNT', 'CONNECT'),
  ('41000000-0000-0000-0000-000000000002', 'ACCOUNT.DISCONNECT', 'Ngắt kết nối tài khoản sàn', 'ACCOUNT', 'DISCONNECT'),
  ('41000000-0000-0000-0000-000000000003', 'PRODUCT.READ', 'Xem sản phẩm', 'PRODUCT', 'READ'),
  ('41000000-0000-0000-0000-000000000004', 'PRODUCT.CREATE', 'Tạo sản phẩm', 'PRODUCT', 'CREATE'),
  ('41000000-0000-0000-0000-000000000005', 'PRODUCT.UPDATE', 'Cập nhật sản phẩm', 'PRODUCT', 'UPDATE'),
  ('41000000-0000-0000-0000-000000000006', 'PRODUCT.DELETE', 'Xóa sản phẩm', 'PRODUCT', 'DELETE'),
  ('41000000-0000-0000-0000-000000000007', 'ORDER.READ', 'Xem đơn hàng', 'ORDER', 'READ'),
  ('41000000-0000-0000-0000-000000000008', 'ORDER.FULFILL', 'Xử lý giao hàng', 'ORDER', 'FULFILL'),
  ('41000000-0000-0000-0000-000000000009', 'ORDER.CANCEL', 'Hủy hoặc từ chối hủy đơn', 'ORDER', 'CANCEL'),
  ('41000000-0000-0000-0000-000000000010', 'CHAT.READ', 'Xem hội thoại', 'CHAT', 'READ'),
  ('41000000-0000-0000-0000-000000000011', 'CHAT.REPLY', 'Trả lời hội thoại', 'CHAT', 'REPLY'),
  ('41000000-0000-0000-0000-000000000012', 'CHAT.ASSIGN', 'Phân công hội thoại', 'CHAT', 'ASSIGN'),
  ('41000000-0000-0000-0000-000000000013', 'AI.SUGGEST', 'Tạo đề xuất AI', 'AI', 'SUGGEST'),
  ('41000000-0000-0000-0000-000000000014', 'AI.APPROVE', 'Duyệt câu trả lời AI', 'AI', 'APPROVE'),
  ('41000000-0000-0000-0000-000000000015', 'AI.CONFIGURE', 'Cấu hình AI và RAG', 'AI', 'CONFIGURE'),
  ('41000000-0000-0000-0000-000000000016', 'REPORT.READ', 'Xem báo cáo', 'REPORT', 'READ'),
  ('41000000-0000-0000-0000-000000000017', 'REPORT.CONFIGURE', 'Cấu hình báo cáo', 'REPORT', 'CONFIGURE')
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_code = VALUES(resource_code),
  action_code = VALUES(action_code);

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT
  '40000000-0000-0000-0000-000000000001',
  id
FROM permissions
WHERE permission_code IN (
  'ACCOUNT.CONNECT', 'ACCOUNT.DISCONNECT',
  'PRODUCT.READ', 'PRODUCT.CREATE', 'PRODUCT.UPDATE', 'PRODUCT.DELETE',
  'ORDER.READ', 'ORDER.FULFILL', 'ORDER.CANCEL',
  'CHAT.READ', 'CHAT.REPLY', 'CHAT.ASSIGN',
  'AI.SUGGEST', 'AI.APPROVE', 'AI.CONFIGURE',
  'REPORT.READ', 'REPORT.CONFIGURE'
);

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT
  '40000000-0000-0000-0000-000000000002',
  id
FROM permissions
WHERE permission_code IN (
  'PRODUCT.READ', 'ORDER.READ',
  'CHAT.READ', 'CHAT.REPLY', 'CHAT.ASSIGN',
  'AI.SUGGEST', 'AI.APPROVE'
);

INSERT INTO tenant_user_roles (
  tenant_user_id, tenant_id, role_id, role_scope_key, assigned_by_user_id
) VALUES (
  '30000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000001',
  '40000000-0000-0000-0000-000000000001',
  '00000000-0000-0000-0000-000000000000',
  NULL
) ON DUPLICATE KEY UPDATE
  tenant_id = VALUES(tenant_id),
  role_scope_key = VALUES(role_scope_key),
  role_id = VALUES(role_id);

INSERT INTO marketplaces (
  id, marketplace_code, marketplace_name, adapter_code, mock_base_url,
  is_active, capabilities_json
) VALUES
  (
    '50000000-0000-0000-0000-000000000001',
    'TIKTOK_SHOP',
    'TikTok Shop',
    'TIKTOK_SHOP_MOCK_V1',
    'http://localhost:4011',
    1,
    JSON_OBJECT('chat', TRUE, 'order', TRUE, 'product', TRUE, 'webhook', TRUE)
  ),
  (
    '50000000-0000-0000-0000-000000000002',
    'LAZADA',
    'Lazada',
    'LAZADA_MOCK_V1',
    'http://localhost:4012/rest',
    1,
    JSON_OBJECT('chat', TRUE, 'order', TRUE, 'product', TRUE, 'push', TRUE)
  )
ON DUPLICATE KEY UPDATE
  marketplace_name = VALUES(marketplace_name),
  adapter_code = VALUES(adapter_code),
  mock_base_url = VALUES(mock_base_url),
  is_active = VALUES(is_active),
  capabilities_json = VALUES(capabilities_json);

INSERT INTO customer_segment_definitions (
  segment_code, segment_name_vi, description, is_active
) VALUES
  ('NEW', 'Khách mới', 'Mới xuất hiện, chưa có đơn hoàn tất', 1),
  ('FIRST_TIME_BUYER', 'Khách mua lần đầu', 'Có đúng một lần mua đầu tiên', 1),
  ('LOYAL', 'Khách thân thiết', 'Mua lại và tương tác tích cực nhiều lần', 1),
  ('LIKELY_TO_REPURCHASE', 'Có khả năng mua lại', 'Tín hiệu chu kỳ hoặc nhu cầu mua lại cao', 1),
  ('LIKELY_TO_CONVERT', 'Có khả năng chốt đơn', 'Tín hiệu hỏi mua, xem, giỏ hàng và ý định cao', 1),
  ('CHURN_RISK', 'Có nguy cơ rời bỏ', 'Tần suất mua/tương tác giảm hoặc có trải nghiệm xấu', 1),
  ('PRODUCT_DISSATISFIED', 'Không hài lòng về sản phẩm', 'Phản hồi, trả hàng hoặc cảm xúc tiêu cực về sản phẩm', 1)
ON DUPLICATE KEY UPDATE
  segment_name_vi = VALUES(segment_name_vi),
  description = VALUES(description),
  is_active = VALUES(is_active);

INSERT INTO analytics_alert_rules (
  id, tenant_id, rule_code, alert_type, lookback_days,
  minimum_order_count, threshold_value, comparison_operator, severity, is_active
) VALUES
  (
    '60000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    'PRODUCT_CANCEL_RATE_7D',
    'HIGH_CANCEL_RATE',
    7,
    5,
    0.200000,
    'GTE',
    'WARNING',
    1
  ),
  (
    '60000000-0000-0000-0000-000000000002',
    '20000000-0000-0000-0000-000000000001',
    'PRODUCT_RETURN_RATE_30D',
    'HIGH_RETURN_RATE',
    30,
    5,
    0.100000,
    'GTE',
    'WARNING',
    1
  )
ON DUPLICATE KEY UPDATE
  lookback_days = VALUES(lookback_days),
  minimum_order_count = VALUES(minimum_order_count),
  threshold_value = VALUES(threshold_value),
  severity = VALUES(severity),
  is_active = VALUES(is_active);

-- ============================================================================
-- 13. Service-layer invariants (must be enforced in application transactions)
-- ============================================================================
-- 1. Every tenant-scoped FK pair must point to rows of the same tenant. Some
--    critical paths are protected by composite foreign keys, and services must
--    still include tenant_id in every authorization and mutation query.
-- 2. A tenant user with must_change_password=1 may only access password-change,
--    logout and support endpoints. Set it to 0 only in the same transaction that
--    appends tenant_password_history and increments credential_version.
-- 3. Only platform_admins may access tenants, subscriptions and provisioned-account
--    administration. A role inside a tenant never grants platform-owner scope.
-- 4. External IDs are unique only within marketplace_account_id.
-- 5. Never auto-link marketplace_customers by display name, avatar or masked PII.
-- 6. Validate the order state machine before updating canonical_status. Append
--    order_status_history in the same transaction.
-- 7. Product/order create-update-delete from the management UI creates an
--    outbound_operation and one target per marketplace account. Do not mark the
--    local action SUCCEEDED until the corresponding target succeeds.
-- 8. Use only active_message_templates. Re-check status and valid time in the
--    write transaction to avoid a race at expiration.
-- 9. AI auto-send requires every configured ai_quality_checks row to pass.
--    Otherwise create human_handoffs + notifications and set ai_mode appropriately.
-- 10. AI feedback is not immediate self-training. A human review must set
--     APPROVED_FOR_LEARNING before creating ai_learning_examples.
-- 11. Daily analytics jobs must be idempotent UPSERTs. Weekly/monthly/yearly
--     reports aggregate the daily facts in the tenant's timezone.
-- 12. Delete is normally soft-delete for business entities. Historical order,
--     message, status, AI audit and report-delivery rows are retained by policy.
-- 13. OAuth state is random, hashed at rest, bound to the initiating user/tenant,
--     expires quickly and is consumed exactly once.
-- 14. Workers claim webhook, sync, outbound and report jobs with locked_by plus
--     locked_until. Expired leases may be reclaimed; active leases may not.
-- 15. PII logs and AI context obey data_protection_policies. Full addresses and
--     contact values are encrypted; searchable contact equality uses keyed HMAC.
-- ============================================================================
