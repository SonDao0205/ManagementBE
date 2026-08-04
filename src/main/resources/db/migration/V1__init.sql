-- ============================================================================
-- OmnichannelPOS Database
-- Target       : PostgreSQL 17+
-- Database     : omnichannel_pos
-- Encoding     : UTF8
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
--   * Stable reference/demo records use INSERT ... ON CONFLICT DO UPDATE.
-- ============================================================================

-- ============================================================================
-- 1. Platform owner, tenants, subscriptions and login
-- ============================================================================

CREATE TABLE IF NOT EXISTS platform_admins (
  id VARCHAR(36) NOT NULL,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  password_algorithm VARCHAR(30) NOT NULL DEFAULT 'ARGON2ID',
  display_name VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  failed_login_count SMALLINT NOT NULL DEFAULT 0,
  locked_until TIMESTAMPTZ(3) NULL,
  last_login_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_platform_admins_email UNIQUE (email),
  CONSTRAINT ck_platform_admins_algorithm
      CHECK (password_algorithm IN ('ARGON2ID', 'BCRYPT', 'SCRYPT', 'PBKDF2')),
  CONSTRAINT ck_platform_admins_status
      CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);
COMMENT ON TABLE platform_admins IS 'System-owner accounts; only these accounts may use the tenant rental administration page';

CREATE TABLE IF NOT EXISTS tenants (
  id VARCHAR(36) NOT NULL,
  tenant_code VARCHAR(50) NOT NULL,
  tenant_name VARCHAR(255) NOT NULL,
  legal_name VARCHAR(255) NULL,
  contact_email VARCHAR(255) NULL,
  contact_phone_encrypted TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  timezone_name VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  default_currency VARCHAR(3) NOT NULL DEFAULT 'VND',
  settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  provisioned_by_admin_id VARCHAR(36) NULL,
  provisioned_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  suspended_at TIMESTAMPTZ(3) NULL,
  closed_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_tenants_code UNIQUE (tenant_code),
  CONSTRAINT fk_tenants_provisioned_by
      FOREIGN KEY (provisioned_by_admin_id) REFERENCES platform_admins(id)
      ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_tenants_status
      CHECK (status IN ('TRIAL', 'ACTIVE', 'SUSPENDED', 'CLOSED'))
);
COMMENT ON TABLE tenants IS 'Organizations renting OmnichannelPOS; tenant boundary for business data';
CREATE INDEX idx_tenants_status ON tenants (status, updated_at);

CREATE TABLE IF NOT EXISTS subscription_plans (
  id VARCHAR(36) NOT NULL,
  plan_code VARCHAR(50) NOT NULL,
  plan_name VARCHAR(150) NOT NULL,
  billing_period VARCHAR(20) NOT NULL,
  price_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'VND',
  limits_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  features_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_subscription_plans_code UNIQUE (plan_code),
  CONSTRAINT ck_subscription_plans_period
      CHECK (billing_period IN ('MONTHLY', 'QUARTERLY', 'YEARLY', 'CUSTOM')),
  CONSTRAINT ck_subscription_plans_price CHECK (price_amount >= 0),
  CONSTRAINT ck_subscription_plans_status
      CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED'))
);
COMMENT ON TABLE subscription_plans IS 'Rental plans managed by the platform owner';

CREATE TABLE IF NOT EXISTS tenant_subscriptions (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  subscription_plan_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'TRIAL',
  starts_at TIMESTAMPTZ(3) NOT NULL,
  trial_ends_at TIMESTAMPTZ(3) NULL,
  current_period_ends_at TIMESTAMPTZ(3) NULL,
  cancelled_at TIMESTAMPTZ(3) NULL,
  notes TEXT NULL,
  created_by_admin_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE tenant_subscriptions IS 'Rental/account lifecycle visible only to platform-owner administration';
CREATE INDEX idx_tenant_subscriptions_tenant_status ON tenant_subscriptions (tenant_id, status);
CREATE INDEX idx_tenant_subscriptions_expiry ON tenant_subscriptions (status, current_period_ends_at);

CREATE TABLE IF NOT EXISTS tenant_users (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  email VARCHAR(255) NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  avatar_url TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'INVITED',
  locale VARCHAR(20) NOT NULL DEFAULT 'vi-VN',
  provisioned_by_admin_id VARCHAR(36) NULL,
  provisioned_by_user_id VARCHAR(36) NULL,
  last_login_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_tenant_users_tenant_email UNIQUE (tenant_id, email),
  CONSTRAINT uq_tenant_users_email UNIQUE (email),
  CONSTRAINT uq_tenant_users_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE tenant_users IS 'Employees/managers who log in under exactly one tenant';
CREATE INDEX idx_tenant_users_status ON tenant_users (tenant_id, status);

CREATE TABLE IF NOT EXISTS tenant_user_credentials (
  tenant_user_id VARCHAR(36) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  password_algorithm VARCHAR(30) NOT NULL DEFAULT 'ARGON2ID',
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  password_changed_at TIMESTAMPTZ(3) NULL,
  password_expires_at TIMESTAMPTZ(3) NULL,
  failed_login_count SMALLINT NOT NULL DEFAULT 0,
  locked_until TIMESTAMPTZ(3) NULL,
  reset_token_hash VARCHAR(64) NULL,
  reset_token_expires_at TIMESTAMPTZ(3) NULL,
  credential_version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_user_id),
  CONSTRAINT uq_tenant_user_credentials_reset UNIQUE (reset_token_hash),
  CONSTRAINT fk_tenant_user_credentials_user
      FOREIGN KEY (tenant_user_id) REFERENCES tenant_users(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_tenant_user_credentials_algorithm
      CHECK (password_algorithm IN ('ARGON2ID', 'BCRYPT', 'SCRYPT', 'PBKDF2')),
  CONSTRAINT ck_tenant_user_credentials_must_change
      CHECK (must_change_password IN (FALSE, TRUE)),
  CONSTRAINT ck_tenant_user_credentials_version CHECK (credential_version > 0)
);
COMMENT ON TABLE tenant_user_credentials IS 'Tenant login secrets and mandatory first-login password-change flag';

CREATE TABLE IF NOT EXISTS tenant_password_history (
  id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
  tenant_user_id VARCHAR(36) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  password_algorithm VARCHAR(30) NOT NULL,
  changed_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  changed_by_type VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_tenant_password_history_user
      FOREIGN KEY (tenant_user_id) REFERENCES tenant_users(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_tenant_password_history_actor
      CHECK (changed_by_type IN ('PLATFORM_ADMIN', 'TENANT_USER', 'SYSTEM_RESET'))
);
COMMENT ON TABLE tenant_password_history IS 'Recent password hashes used to prevent password reuse';
CREATE INDEX idx_tenant_password_history_user_time ON tenant_password_history (tenant_user_id, changed_at DESC);

CREATE TABLE IF NOT EXISTS roles (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NULL,
  tenant_scope_key VARCHAR(36) NOT NULL
      DEFAULT '00000000-0000-0000-0000-000000000000',
  role_code VARCHAR(50) NOT NULL,
  role_name VARCHAR(100) NOT NULL,
  description TEXT NULL,
  is_system BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_roles_id_scope UNIQUE (id, tenant_scope_key),
  CONSTRAINT uq_roles_scope_code UNIQUE (tenant_scope_key, role_code),
  CONSTRAINT fk_roles_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_roles_system CHECK (is_system IN (FALSE, TRUE)),
  CONSTRAINT ck_roles_scope CHECK (
      (
        tenant_id IS NULL
        AND tenant_scope_key = '00000000-0000-0000-0000-000000000000'
        AND is_system = TRUE
      )
      OR (
        tenant_id IS NOT NULL
        AND tenant_scope_key = tenant_id
      )
    )
);
COMMENT ON TABLE roles IS 'Tenant-scoped and system tenant-user roles';

CREATE TABLE IF NOT EXISTS permissions (
  id VARCHAR(36) NOT NULL,
  permission_code VARCHAR(100) NOT NULL,
  permission_name VARCHAR(150) NOT NULL,
  resource_code VARCHAR(50) NOT NULL,
  action_code VARCHAR(50) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_permissions_code UNIQUE (permission_code)
);
COMMENT ON TABLE permissions IS 'Atomic application permissions';

CREATE TABLE IF NOT EXISTS role_permissions (
  role_id VARCHAR(36) NOT NULL,
  permission_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_role_permissions_role
      FOREIGN KEY (role_id) REFERENCES roles(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_role_permissions_permission
      FOREIGN KEY (permission_id) REFERENCES permissions(id)
      ON UPDATE RESTRICT ON DELETE CASCADE
);
COMMENT ON TABLE role_permissions IS 'Permissions granted to a role';

CREATE TABLE IF NOT EXISTS tenant_user_roles (
  tenant_user_id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  role_id VARCHAR(36) NOT NULL,
  role_scope_key VARCHAR(36) NOT NULL,
  assigned_by_user_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_user_id, role_id),
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
);
COMMENT ON TABLE tenant_user_roles IS 'Role assignments for tenant users';
CREATE INDEX idx_tenant_user_roles_tenant ON tenant_user_roles (tenant_id, role_id);

CREATE TABLE IF NOT EXISTS login_sessions (
  id VARCHAR(36) NOT NULL,
  actor_type VARCHAR(20) NOT NULL,
  platform_admin_id VARCHAR(36) NULL,
  tenant_user_id VARCHAR(36) NULL,
  session_token_hash VARCHAR(64) NOT NULL,
  auth_stage VARCHAR(30) NOT NULL DEFAULT 'AUTHENTICATED',
  ip_address VARCHAR(45) NULL,
  user_agent VARCHAR(500) NULL,
  issued_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at TIMESTAMPTZ(3) NOT NULL,
  revoked_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_login_sessions_token UNIQUE (session_token_hash),
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
);
COMMENT ON TABLE login_sessions IS 'Hashed sessions for both platform owner and tenant users';
CREATE INDEX idx_login_sessions_tenant_user ON login_sessions (tenant_user_id, expires_at);
CREATE INDEX idx_login_sessions_admin ON login_sessions (platform_admin_id, expires_at);

CREATE TABLE IF NOT EXISTS security_audit_logs (
  id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
  tenant_id VARCHAR(36) NULL,
  actor_type VARCHAR(20) NOT NULL,
  actor_id VARCHAR(36) NULL,
  action_code VARCHAR(100) NOT NULL,
  target_type VARCHAR(50) NULL,
  target_id VARCHAR(200) NULL,
  result VARCHAR(20) NOT NULL,
  ip_address VARCHAR(45) NULL,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  occurred_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_security_audit_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_security_audit_actor
      CHECK (actor_type IN ('PLATFORM_ADMIN', 'TENANT_USER', 'SYSTEM', 'AI')),
  CONSTRAINT ck_security_audit_result
      CHECK (result IN ('SUCCEEDED', 'FAILED', 'DENIED'))
);
COMMENT ON TABLE security_audit_logs IS 'Sanitized audit trail including tenant provisioning and access denials';
CREATE INDEX idx_security_audit_tenant_time ON security_audit_logs (tenant_id, occurred_at DESC);
CREATE INDEX idx_security_audit_actor_time ON security_audit_logs (actor_type, actor_id, occurred_at DESC);
CREATE INDEX idx_security_audit_action_time ON security_audit_logs (action_code, occurred_at DESC);

CREATE TABLE IF NOT EXISTS data_protection_policies (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  data_category VARCHAR(50) NOT NULL,
  retention_days INTEGER NOT NULL,
  encrypt_at_rest BOOLEAN NOT NULL DEFAULT TRUE,
  redact_in_logs BOOLEAN NOT NULL DEFAULT TRUE,
  allow_ai_processing BOOLEAN NOT NULL DEFAULT FALSE,
  purge_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  policy_version VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_data_protection_policy_category UNIQUE (tenant_id, data_category),
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
      encrypt_at_rest IN (FALSE, TRUE)
      AND redact_in_logs IN (FALSE, TRUE)
      AND allow_ai_processing IN (FALSE, TRUE)
      AND purge_enabled IN (FALSE, TRUE)
    ),
  CONSTRAINT ck_data_protection_policy_retention CHECK (retention_days > 0)
);
COMMENT ON TABLE data_protection_policies IS 'Tenant PII encryption, redaction, AI-use and retention policy by data category';

-- ============================================================================
-- 2. Supported marketplaces and connected shops
-- ============================================================================

CREATE TABLE IF NOT EXISTS marketplaces (
  id VARCHAR(36) NOT NULL,
  marketplace_code VARCHAR(30) NOT NULL,
  marketplace_name VARCHAR(100) NOT NULL,
  adapter_code VARCHAR(50) NOT NULL,
  mock_base_url VARCHAR(500) NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  capabilities_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_marketplaces_code UNIQUE (marketplace_code),
  CONSTRAINT uq_marketplaces_adapter UNIQUE (adapter_code),
  CONSTRAINT ck_marketplaces_code
      CHECK (marketplace_code IN ('TIKTOK_SHOP', 'LAZADA')),
  CONSTRAINT ck_marketplaces_active CHECK (is_active IN (FALSE, TRUE))
);
COMMENT ON TABLE marketplaces IS 'Allow-list of marketplace adapters; initially only two simulators';

CREATE TABLE IF NOT EXISTS marketplace_accounts (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_id VARCHAR(36) NOT NULL,
  external_account_id VARCHAR(200) NOT NULL,
  shop_cipher VARCHAR(255) NULL,
  external_shop_name VARCHAR(255) NOT NULL,
  site_id VARCHAR(10) NOT NULL DEFAULT 'VN',
  currency VARCHAR(3) NOT NULL DEFAULT 'VND',
  timezone_name VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  connection_status VARCHAR(20) NOT NULL DEFAULT 'CONNECTED',
  authorized_at TIMESTAMPTZ(3) NULL,
  expires_at TIMESTAMPTZ(3) NULL,
  last_verified_at TIMESTAMPTZ(3) NULL,
  settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_by_user_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_marketplace_accounts_external_owner UNIQUE (marketplace_id, external_account_id),
  CONSTRAINT uq_marketplace_accounts_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE marketplace_accounts IS 'TikTok shops and Lazada sellers connected by a tenant';
CREATE INDEX idx_marketplace_accounts_tenant_status ON marketplace_accounts (tenant_id, connection_status);
CREATE INDEX idx_marketplace_accounts_expiry ON marketplace_accounts (connection_status, expires_at);

CREATE TABLE IF NOT EXISTS marketplace_credentials (
  id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  app_key VARCHAR(150) NULL,
  access_token_encrypted TEXT NOT NULL,
  refresh_token_encrypted TEXT NULL,
  signing_secret_encrypted TEXT NULL,
  scopes_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  encryption_key_version VARCHAR(30) NOT NULL,
  access_token_expires_at TIMESTAMPTZ(3) NULL,
  refresh_token_expires_at TIMESTAMPTZ(3) NULL,
  last_refreshed_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_marketplace_credentials_account UNIQUE (marketplace_account_id),
  CONSTRAINT fk_marketplace_credentials_account
      FOREIGN KEY (marketplace_account_id) REFERENCES marketplace_accounts(id)
      ON UPDATE RESTRICT ON DELETE CASCADE
);
COMMENT ON TABLE marketplace_credentials IS 'Encrypted credentials; application KMS owns encryption keys';

CREATE TABLE IF NOT EXISTS marketplace_connection_history (
  id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
  marketplace_account_id VARCHAR(36) NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status VARCHAR(20) NOT NULL,
  reason_code VARCHAR(100) NULL,
  details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  changed_by_user_id VARCHAR(36) NULL,
  occurred_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_marketplace_connection_history_account
      FOREIGN KEY (marketplace_account_id) REFERENCES marketplace_accounts(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_marketplace_connection_history_user
      FOREIGN KEY (changed_by_user_id) REFERENCES tenant_users(id)
      ON UPDATE RESTRICT ON DELETE SET NULL
);
COMMENT ON TABLE marketplace_connection_history IS 'Authorization/connection lifecycle audit';
CREATE INDEX idx_marketplace_connection_history_account_time ON marketplace_connection_history (marketplace_account_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS oauth_authorization_sessions (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  tenant_user_id VARCHAR(36) NOT NULL,
  marketplace_id VARCHAR(36) NOT NULL,
  state_hash VARCHAR(64) NOT NULL,
  pkce_verifier_encrypted TEXT NULL,
  requested_scopes_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  return_url VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  expires_at TIMESTAMPTZ(3) NOT NULL,
  consumed_at TIMESTAMPTZ(3) NULL,
  failure_code VARCHAR(100) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_oauth_authorization_sessions_state UNIQUE (state_hash),
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
);
COMMENT ON TABLE oauth_authorization_sessions IS 'Single-use OAuth state/PKCE session bound to the initiating tenant user';
CREATE INDEX idx_oauth_authorization_sessions_expiry ON oauth_authorization_sessions (status, expires_at);

-- ============================================================================
-- 3. Customers, identity isolation and behavioral interests
-- ============================================================================

CREATE TABLE IF NOT EXISTS customers (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  customer_code VARCHAR(100) NOT NULL,
  display_name VARCHAR(255) NULL,
  phone_normalized_encrypted TEXT NULL,
  email_normalized_encrypted TEXT NULL,
  phone_lookup_hmac VARCHAR(64) NULL,
  email_lookup_hmac VARCHAR(64) NULL,
  pii_key_version VARCHAR(30) NULL,
  identity_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
  merged_into_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_customers_tenant_code UNIQUE (tenant_id, customer_code),
  CONSTRAINT uq_customers_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT uq_customers_tenant_phone_lookup UNIQUE (tenant_id, phone_lookup_hmac),
  CONSTRAINT uq_customers_tenant_email_lookup UNIQUE (tenant_id, email_lookup_hmac),
  CONSTRAINT fk_customers_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_customers_merged_into
      FOREIGN KEY (merged_into_id) REFERENCES customers(id)
      ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_customers_identity_status
      CHECK (identity_status IN ('UNVERIFIED', 'VERIFIED', 'MERGED'))
);
COMMENT ON TABLE customers IS 'Optional verified cross-channel customer profile; never auto-created from a matching name';
CREATE INDEX idx_customers_tenant_status ON customers (tenant_id, identity_status, updated_at);

CREATE TABLE IF NOT EXISTS marketplace_customers (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  external_customer_id VARCHAR(200) NOT NULL,
  external_im_user_id VARCHAR(200) NULL,
  display_name VARCHAR(255) NULL,
  avatar_url TEXT NULL,
  phone_masked VARCHAR(100) NULL,
  email_masked VARCHAR(255) NULL,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  first_seen_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_marketplace_customers_account_external UNIQUE (marketplace_account_id, external_customer_id),
  CONSTRAINT uq_marketplace_customers_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT fk_marketplace_customers_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_marketplace_customers_account_tenant
      FOREIGN KEY (marketplace_account_id, tenant_id)
      REFERENCES marketplace_accounts(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE RESTRICT
);
COMMENT ON TABLE marketplace_customers IS 'Buyer identity remains separate per connected marketplace account';
CREATE INDEX idx_marketplace_customers_tenant_seen ON marketplace_customers (tenant_id, last_seen_at DESC);
CREATE INDEX idx_marketplace_customers_im ON marketplace_customers (marketplace_account_id, external_im_user_id);

CREATE TABLE IF NOT EXISTS customer_identity_links (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  customer_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  link_method VARCHAR(30) NOT NULL,
  verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  verified_by_user_id VARCHAR(36) NULL,
  verified_at TIMESTAMPTZ(3) NULL,
  evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_customer_identity_links_marketplace_customer UNIQUE (marketplace_customer_id),
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
);
COMMENT ON TABLE customer_identity_links IS 'Explicitly verified identity links; names and masked contact fields are never sufficient';
CREATE INDEX idx_customer_identity_links_customer ON customer_identity_links (customer_id, verification_status);

CREATE TABLE IF NOT EXISTS customer_behavior_events (
  event_id VARCHAR(100) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  source_session_id VARCHAR(100) NULL,
  marketplace_code VARCHAR(30) NOT NULL,
  event_name VARCHAR(50) NOT NULL,
  screen VARCHAR(100) NULL,
  entity_type VARCHAR(50) NULL,
  entity_external_id VARCHAR(200) NULL,
  properties_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  occurred_at TIMESTAMPTZ(3) NOT NULL,
  received_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (marketplace_account_id, event_id),
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
);
COMMENT ON TABLE customer_behavior_events IS 'Raw customer storefront behavior used as explainable interest/segment signals';
CREATE INDEX idx_customer_behavior_tenant_time ON customer_behavior_events (tenant_id, occurred_at DESC);
CREATE INDEX idx_customer_behavior_customer_time ON customer_behavior_events (marketplace_customer_id, occurred_at DESC);
CREATE INDEX idx_customer_behavior_entity ON customer_behavior_events (tenant_id, entity_type, entity_external_id, occurred_at);

-- ============================================================================
-- 4. Canonical products, variants and marketplace mappings
-- ============================================================================

CREATE TABLE IF NOT EXISTS products (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  product_code VARCHAR(100) NOT NULL,
  product_name VARCHAR(500) NOT NULL,
  description TEXT NULL,
  brand_name VARCHAR(255) NULL,
  internal_category_code VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  version INTEGER NOT NULL DEFAULT 1,
  created_by_user_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_products_tenant_code UNIQUE (tenant_id, product_code),
  CONSTRAINT uq_products_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT fk_products_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_products_creator_tenant
      FOREIGN KEY (created_by_user_id, tenant_id) REFERENCES tenant_users(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_products_status
      CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_products_version CHECK (version > 0)
);
COMMENT ON TABLE products IS 'Canonical product edited from the centralized product screen';
CREATE INDEX idx_products_tenant_status ON products (tenant_id, status, updated_at DESC);
CREATE INDEX idx_products_tenant_name ON products (tenant_id, product_name);

CREATE TABLE IF NOT EXISTS product_variants (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NOT NULL,
  variant_code VARCHAR(100) NOT NULL,
  seller_sku VARCHAR(200) NOT NULL,
  variant_name VARCHAR(255) NULL,
  attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  price DECIMAL(18,2) NOT NULL DEFAULT 0,
  compare_at_price DECIMAL(18,2) NULL,
  currency VARCHAR(3) NOT NULL DEFAULT 'VND',
  stock_on_hand INT NOT NULL DEFAULT 0,
  reserved_stock INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_product_variants_product_code UNIQUE (product_id, variant_code),
  CONSTRAINT uq_product_variants_tenant_sku UNIQUE (tenant_id, seller_sku),
  CONSTRAINT uq_product_variants_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE product_variants IS 'Canonical size/color/etc. variants and tenant seller SKUs';
CREATE INDEX idx_product_variants_product_status ON product_variants (product_id, status);

CREATE TABLE IF NOT EXISTS product_media (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NOT NULL,
  product_variant_id VARCHAR(36) NULL,
  media_type VARCHAR(20) NOT NULL,
  storage_key VARCHAR(500) NOT NULL,
  public_url TEXT NOT NULL,
  checksum_sha256 VARCHAR(64) NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_product_media_id_tenant UNIQUE (id, tenant_id),
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
  CONSTRAINT ck_product_media_primary CHECK (is_primary IN (FALSE, TRUE))
);
COMMENT ON TABLE product_media IS 'Canonical product and variant media';
CREATE INDEX idx_product_media_product_sort ON product_media (product_id, sort_order);
CREATE INDEX idx_product_media_variant ON product_media (product_variant_id);

CREATE TABLE IF NOT EXISTS marketplace_products (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  external_product_id VARCHAR(200) NOT NULL,
  external_category_id VARCHAR(200) NULL,
  external_title VARCHAR(500) NULL,
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  sync_status VARCHAR(20) NOT NULL DEFAULT 'SYNCED',
  external_version VARCHAR(100) NULL,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  last_synced_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_marketplace_products_external UNIQUE (marketplace_account_id, external_product_id),
  CONSTRAINT uq_marketplace_products_mapping UNIQUE (product_id, marketplace_account_id),
  CONSTRAINT uq_marketplace_products_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE marketplace_products IS 'Mapping between a canonical product and each marketplace listing';
CREATE INDEX idx_marketplace_products_sync ON marketplace_products (tenant_id, sync_status, updated_at);

CREATE TABLE IF NOT EXISTS marketplace_product_variants (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_product_id VARCHAR(36) NOT NULL,
  product_variant_id VARCHAR(36) NOT NULL,
  external_sku_id VARCHAR(200) NOT NULL,
  external_seller_sku VARCHAR(200) NULL,
  external_price DECIMAL(18,2) NULL,
  external_stock INT NULL,
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  sync_status VARCHAR(20) NOT NULL DEFAULT 'SYNCED',
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  last_synced_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_marketplace_product_variants_external UNIQUE (marketplace_product_id, external_sku_id),
  CONSTRAINT uq_marketplace_product_variants_mapping UNIQUE (marketplace_product_id, product_variant_id),
  CONSTRAINT uq_marketplace_product_variants_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE marketplace_product_variants IS 'Mapping between canonical variants and marketplace SKUs';
CREATE INDEX idx_marketplace_product_variants_sync ON marketplace_product_variants (tenant_id, sync_status, updated_at);

CREATE TABLE IF NOT EXISTS product_sync_history (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_product_id VARCHAR(36) NOT NULL,
  direction VARCHAR(20) NOT NULL,
  change_type VARCHAR(30) NOT NULL,
  before_json JSONB NULL,
  after_json JSONB NULL,
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(100) NULL,
  occurred_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE product_sync_history IS 'Auditable centralized product CRUD and marketplace synchronization';
CREATE INDEX idx_product_sync_history_product_time ON product_sync_history (marketplace_product_id, occurred_at DESC);
CREATE INDEX idx_product_sync_history_tenant_status ON product_sync_history (tenant_id, status, occurred_at);

-- ============================================================================
-- 5. Canonical orders, order items, shipments and marketplace actions
-- ============================================================================

CREATE TABLE IF NOT EXISTS orders (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NULL,
  external_order_id VARCHAR(200) NOT NULL,
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  payment_status VARCHAR(30) NOT NULL DEFAULT 'UNPAID',
  refund_status VARCHAR(30) NOT NULL DEFAULT 'NONE',
  currency VARCHAR(3) NOT NULL DEFAULT 'VND',
  subtotal_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  shipping_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  shipping_address_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  billing_address_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  shipping_address_encrypted TEXT NULL,
  billing_address_encrypted TEXT NULL,
  pii_key_version VARCHAR(30) NULL,
  buyer_note TEXT NULL,
  internal_note TEXT NULL,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  external_created_at TIMESTAMPTZ(3) NOT NULL,
  external_updated_at TIMESTAMPTZ(3) NOT NULL,
  last_synced_at TIMESTAMPTZ(3) NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_orders_external UNIQUE (marketplace_account_id, external_order_id),
  CONSTRAINT uq_orders_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE orders IS 'Marketplace orders normalized to the ten required Omnichannel statuses';
CREATE INDEX idx_orders_tenant_status_time ON orders (tenant_id, canonical_status, external_created_at DESC);
CREATE INDEX idx_orders_account_status_time ON orders (marketplace_account_id, canonical_status, external_created_at DESC);
CREATE INDEX idx_orders_customer_time ON orders (marketplace_customer_id, external_created_at DESC);

CREATE TABLE IF NOT EXISTS order_items (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NULL,
  product_variant_id VARCHAR(36) NULL,
  marketplace_product_id VARCHAR(36) NULL,
  marketplace_product_variant_id VARCHAR(36) NULL,
  external_order_item_id VARCHAR(200) NOT NULL,
  external_product_id VARCHAR(200) NOT NULL,
  external_sku_id VARCHAR(200) NULL,
  seller_sku_snapshot VARCHAR(200) NULL,
  product_name_snapshot VARCHAR(500) NOT NULL,
  variant_name_snapshot VARCHAR(255) NULL,
  quantity INTEGER NOT NULL,
  unit_price DECIMAL(18,2) NOT NULL,
  discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  paid_amount DECIMAL(18,2) NOT NULL,
  currency VARCHAR(3) NOT NULL DEFAULT 'VND',
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_order_items_external UNIQUE (order_id, external_order_item_id),
  CONSTRAINT uq_order_items_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE order_items IS 'Immutable commercial snapshots for order detail and product analytics';
CREATE INDEX idx_order_items_product_time ON order_items (product_id, created_at);
CREATE INDEX idx_order_items_variant ON order_items (product_variant_id);

CREATE TABLE IF NOT EXISTS shipments (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  external_package_id VARCHAR(200) NOT NULL,
  tracking_number VARCHAR(200) NULL,
  shipping_provider VARCHAR(150) NULL,
  raw_status VARCHAR(100) NOT NULL,
  canonical_status VARCHAR(30) NOT NULL,
  shipping_label_url TEXT NULL,
  package_items_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  ready_to_ship_at TIMESTAMPTZ(3) NULL,
  shipped_at TIMESTAMPTZ(3) NULL,
  delivered_at TIMESTAMPTZ(3) NULL,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_shipments_external UNIQUE (order_id, external_package_id),
  CONSTRAINT uq_shipments_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE shipments IS 'Packages and tracking data available to staff and AI order lookup';
CREATE INDEX idx_shipments_tracking ON shipments (tenant_id, tracking_number);
CREATE INDEX idx_shipments_order_status ON shipments (order_id, canonical_status);

CREATE TABLE IF NOT EXISTS order_status_history (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  order_item_id VARCHAR(36) NULL,
  from_raw_status VARCHAR(100) NULL,
  to_raw_status VARCHAR(100) NOT NULL,
  from_canonical_status VARCHAR(30) NULL,
  to_canonical_status VARCHAR(30) NOT NULL,
  source VARCHAR(30) NOT NULL,
  external_event_id VARCHAR(200) NULL,
  changed_by_user_id VARCHAR(36) NULL,
  reason_code VARCHAR(100) NULL,
  occurred_at TIMESTAMPTZ(3) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_order_status_history_external UNIQUE (order_id, external_event_id),
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
);
COMMENT ON TABLE order_status_history IS 'Append-only raw and canonical order status history';
CREATE INDEX idx_order_status_history_order_time ON order_status_history (order_id, occurred_at);
CREATE INDEX idx_order_status_history_tenant_time ON order_status_history (tenant_id, occurred_at);

CREATE TABLE IF NOT EXISTS order_actions (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  order_item_id VARCHAR(36) NULL,
  requested_by_user_id VARCHAR(36) NULL,
  action_type VARCHAR(40) NOT NULL,
  action_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  reason_code VARCHAR(100) NULL,
  reason_text TEXT NULL,
  request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  response_payload JSONB NULL,
  integration_operation_id VARCHAR(36) NULL,
  requested_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE order_actions IS 'Order CRUD/fulfillment commands that must be synchronized back to the marketplace';
CREATE INDEX idx_order_actions_order_time ON order_actions (order_id, requested_at DESC);
CREATE INDEX idx_order_actions_tenant_status ON order_actions (tenant_id, action_status, requested_at);
CREATE INDEX idx_order_actions_operation ON order_actions (integration_operation_id);

-- ============================================================================
-- 6. Unified Inbox, messages, assignment and time-limited macros/templates
-- ============================================================================

CREATE TABLE IF NOT EXISTS conversations (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  external_conversation_id VARCHAR(200) NOT NULL,
  raw_status VARCHAR(100) NULL,
  internal_status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  assigned_user_id VARCHAR(36) NULL,
  priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  unread_count INTEGER NOT NULL DEFAULT 0,
  last_message_id VARCHAR(36) NULL,
  last_message_preview VARCHAR(500) NULL,
  last_message_at TIMESTAMPTZ(3) NULL,
  ai_mode VARCHAR(20) NOT NULL DEFAULT 'SUGGEST_ONLY',
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  closed_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_conversations_external UNIQUE (marketplace_account_id, external_conversation_id),
  CONSTRAINT uq_conversations_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE conversations IS 'Unified Inbox rows filterable by tenant, marketplace, status and assignee';
CREATE INDEX idx_conversations_unified_inbox ON conversations (tenant_id, internal_status, priority, last_message_at DESC);
CREATE INDEX idx_conversations_marketplace_filter ON conversations (marketplace_account_id, internal_status, last_message_at DESC);
CREATE INDEX idx_conversations_assignee ON conversations (assigned_user_id, internal_status, last_message_at DESC);
CREATE INDEX idx_conversations_customer ON conversations (marketplace_customer_id, last_message_at DESC);

CREATE TABLE IF NOT EXISTS messages (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  external_message_id VARCHAR(200) NULL,
  client_message_id VARCHAR(200) NULL,
  direction VARCHAR(10) NOT NULL,
  sender_type VARCHAR(20) NOT NULL,
  sender_user_id VARCHAR(36) NULL,
  message_type VARCHAR(30) NOT NULL,
  detected_language VARCHAR(20) NULL,
  text_content TEXT NULL,
  content_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  delivery_status VARCHAR(20) NOT NULL,
  moderation_status VARCHAR(20) NOT NULL DEFAULT 'NOT_CHECKED',
  error_code VARCHAR(100) NULL,
  error_message TEXT NULL,
  queued_at TIMESTAMPTZ(3) NULL,
  sent_at TIMESTAMPTZ(3) NULL,
  delivered_at TIMESTAMPTZ(3) NULL,
  read_at TIMESTAMPTZ(3) NULL,
  failed_at TIMESTAMPTZ(3) NULL,
  recalled_at TIMESTAMPTZ(3) NULL,
  external_created_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_messages_external UNIQUE (conversation_id, external_message_id),
  CONSTRAINT uq_messages_client UNIQUE (conversation_id, client_message_id),
  CONSTRAINT uq_messages_id_tenant UNIQUE (id, tenant_id),
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
);
COMMENT ON TABLE messages IS 'Normalized inbound and outbound messages; one row is the AI trigger or final response';
CREATE INDEX idx_messages_conversation_time ON messages (conversation_id, created_at);
CREATE INDEX idx_messages_tenant_direction_time ON messages (tenant_id, direction, created_at);

CREATE TABLE IF NOT EXISTS message_attachments (
  id VARCHAR(36) NOT NULL,
  message_id VARCHAR(36) NOT NULL,
  attachment_type VARCHAR(30) NOT NULL,
  external_resource_id VARCHAR(200) NULL,
  resource_url TEXT NULL,
  thumbnail_url TEXT NULL,
  mime_type VARCHAR(100) NULL,
  file_size_bytes BIGINT NULL,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_message_attachments_message
      FOREIGN KEY (message_id) REFERENCES messages(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_message_attachments_type
      CHECK (attachment_type IN ('IMAGE', 'VIDEO', 'PRODUCT', 'ORDER', 'LOGISTICS', 'COUPON', 'FILE'))
);
COMMENT ON TABLE message_attachments IS 'Message images, videos and product/order/logistics cards';
CREATE INDEX idx_message_attachments_message_sort ON message_attachments (message_id, sort_order);

CREATE TABLE IF NOT EXISTS conversation_status_history (
  id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
  tenant_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  from_status VARCHAR(30) NULL,
  to_status VARCHAR(30) NOT NULL,
  changed_by_type VARCHAR(20) NOT NULL,
  changed_by_user_id VARCHAR(36) NULL,
  reason_code VARCHAR(100) NULL,
  occurred_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE conversation_status_history IS 'Append-only Unified Inbox status transitions';
CREATE INDEX idx_conversation_status_history_time ON conversation_status_history (conversation_id, occurred_at);
CREATE INDEX idx_conversation_status_history_tenant ON conversation_status_history (tenant_id, occurred_at);

CREATE TABLE IF NOT EXISTS message_templates (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  template_code VARCHAR(100) NOT NULL,
  template_name VARCHAR(255) NOT NULL,
  template_type VARCHAR(20) NOT NULL DEFAULT 'TEMPLATE',
  category VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  valid_from TIMESTAMPTZ(3) NULL,
  valid_until TIMESTAMPTZ(3) NULL,
  current_version_number INTEGER NULL,
  created_by_user_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_message_templates_tenant_code UNIQUE (tenant_id, template_code),
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
);
COMMENT ON TABLE message_templates IS 'Tenant-specific templates/macros; application may use only active rows inside validity window';
CREATE INDEX idx_message_templates_active ON message_templates (tenant_id, status, valid_from, valid_until);

CREATE TABLE IF NOT EXISTS message_template_versions (
  id VARCHAR(36) NOT NULL,
  message_template_id VARCHAR(36) NOT NULL,
  version_number INTEGER NOT NULL,
  content_text TEXT NOT NULL,
  variables_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  actions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_by_user_id VARCHAR(36) NULL,
  change_note TEXT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_message_template_versions_number UNIQUE (message_template_id, version_number),
  CONSTRAINT fk_message_template_versions_template
      FOREIGN KEY (message_template_id) REFERENCES message_templates(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_message_template_versions_user
      FOREIGN KEY (created_by_user_id) REFERENCES tenant_users(id)
      ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_message_template_versions_number CHECK (version_number > 0)
);
COMMENT ON TABLE message_template_versions IS 'Immutable template/macro content versions';

CREATE TABLE IF NOT EXISTS conversation_template_usages (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  message_id VARCHAR(36) NULL,
  template_version_id VARCHAR(36) NOT NULL,
  used_by_user_id VARCHAR(36) NULL,
  rendered_content TEXT NOT NULL,
  variables_used_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE conversation_template_usages IS 'Audit of exact macro/template version and rendered text used';
CREATE INDEX idx_conversation_template_usages_conversation ON conversation_template_usages (conversation_id, created_at);

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
  AND (mt.valid_from IS NULL OR mt.valid_from <= CURRENT_TIMESTAMP(3))
  AND (mt.valid_until IS NULL OR mt.valid_until > CURRENT_TIMESTAMP(3));

-- ============================================================================
-- 7. Reliable marketplace integration, synchronization and retry
-- ============================================================================

CREATE TABLE IF NOT EXISTS webhook_inbox (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  external_event_id VARCHAR(200) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  signature_valid BOOLEAN NOT NULL,
  headers_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload_json JSONB NOT NULL,
  processing_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMPTZ(3) NULL,
  locked_by VARCHAR(100) NULL,
  locked_until TIMESTAMPTZ(3) NULL,
  received_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  processed_at TIMESTAMPTZ(3) NULL,
  last_error TEXT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_webhook_inbox_event UNIQUE (marketplace_account_id, external_event_id),
  CONSTRAINT fk_webhook_inbox_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_webhook_inbox_account_tenant
      FOREIGN KEY (marketplace_account_id, tenant_id)
      REFERENCES marketplace_accounts(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_webhook_inbox_signature CHECK (signature_valid IN (FALSE, TRUE)),
  CONSTRAINT ck_webhook_inbox_status
      CHECK (processing_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD'))
);
COMMENT ON TABLE webhook_inbox IS 'Durable, deduplicated TikTok webhook and Lazada push inbox';
CREATE INDEX idx_webhook_inbox_worker ON webhook_inbox (processing_status, next_retry_at, locked_until, received_at);
CREATE INDEX idx_webhook_inbox_tenant_time ON webhook_inbox (tenant_id, received_at DESC);

CREATE TABLE IF NOT EXISTS inbound_processing_attempts (
  id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
  webhook_inbox_id VARCHAR(36) NOT NULL,
  attempt_number INTEGER NOT NULL,
  worker_id VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(100) NULL,
  error_message TEXT NULL,
  started_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_inbound_processing_attempts_number UNIQUE (webhook_inbox_id, attempt_number),
  CONSTRAINT fk_inbound_processing_attempts_inbox
      FOREIGN KEY (webhook_inbox_id) REFERENCES webhook_inbox(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_inbound_processing_attempts_number CHECK (attempt_number > 0),
  CONSTRAINT ck_inbound_processing_attempts_status
      CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED'))
);
COMMENT ON TABLE inbound_processing_attempts IS 'Every attempt to normalize one inbound marketplace event';

CREATE TABLE IF NOT EXISTS sync_checkpoints (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  resource_type VARCHAR(30) NOT NULL,
  cursor_value TEXT NULL,
  watermark_time TIMESTAMPTZ(3) NULL,
  last_success_at TIMESTAMPTZ(3) NULL,
  last_attempt_at TIMESTAMPTZ(3) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'IDLE',
  last_error TEXT NULL,
  locked_by VARCHAR(100) NULL,
  locked_until TIMESTAMPTZ(3) NULL,
  version INTEGER NOT NULL DEFAULT 1,
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_sync_checkpoints_resource UNIQUE (marketplace_account_id, resource_type),
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
);
COMMENT ON TABLE sync_checkpoints IS 'Cursor/watermark for initial sync and incremental reconciliation';
CREATE INDEX idx_sync_checkpoints_worker ON sync_checkpoints (status, locked_until, last_attempt_at);

CREATE TABLE IF NOT EXISTS outbound_operations (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  operation_type VARCHAR(50) NOT NULL,
  entity_type VARCHAR(30) NOT NULL,
  entity_id VARCHAR(36) NOT NULL,
  requested_by_user_id VARCHAR(36) NULL,
  idempotency_key VARCHAR(200) NOT NULL,
  payload_json JSONB NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  target_count INTEGER NOT NULL DEFAULT 0,
  succeeded_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_outbound_operations_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT uq_outbound_operations_idempotency UNIQUE (tenant_id, idempotency_key),
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
);
COMMENT ON TABLE outbound_operations IS 'One canonical user/system operation that may fan out to multiple marketplaces';
CREATE INDEX idx_outbound_operations_worker ON outbound_operations (status, created_at);
CREATE INDEX idx_outbound_operations_entity ON outbound_operations (tenant_id, entity_type, entity_id);

CREATE TABLE IF NOT EXISTS outbound_operation_targets (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  outbound_operation_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  target_external_id VARCHAR(200) NULL,
  adapter_operation VARCHAR(100) NOT NULL,
  target_payload_json JSONB NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMPTZ(3) NULL,
  locked_by VARCHAR(100) NULL,
  locked_until TIMESTAMPTZ(3) NULL,
  external_request_id VARCHAR(200) NULL,
  external_result_id VARCHAR(200) NULL,
  last_error_code VARCHAR(100) NULL,
  last_error_message TEXT NULL,
  started_at TIMESTAMPTZ(3) NULL,
  completed_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_outbound_operation_targets_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT uq_outbound_operation_targets_account UNIQUE (outbound_operation_id, marketplace_account_id),
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
);
COMMENT ON TABLE outbound_operation_targets IS 'Marketplace-specific target; one marketplace can retry without rolling back another';
CREATE INDEX idx_outbound_operation_targets_worker ON outbound_operation_targets (status, next_retry_at, locked_until, created_at);

CREATE TABLE IF NOT EXISTS outbound_attempts (
  id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
  tenant_id VARCHAR(36) NOT NULL,
  outbound_target_id VARCHAR(36) NOT NULL,
  attempt_number INTEGER NOT NULL,
  request_id VARCHAR(200) NOT NULL,
  request_method VARCHAR(10) NOT NULL,
  request_path VARCHAR(500) NOT NULL,
  request_body_json JSONB NULL,
  response_http_status SMALLINT NULL,
  response_code VARCHAR(100) NULL,
  response_body_json JSONB NULL,
  latency_ms INTEGER NULL,
  error_class VARCHAR(100) NULL,
  is_retryable BOOLEAN NOT NULL DEFAULT FALSE,
  started_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_outbound_attempts_number UNIQUE (outbound_target_id, attempt_number),
  CONSTRAINT uq_outbound_attempts_request UNIQUE (request_id),
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
  CONSTRAINT ck_outbound_attempts_retryable CHECK (is_retryable IN (FALSE, TRUE))
);
COMMENT ON TABLE outbound_attempts IS 'Sanitized request/response audit for each outbound marketplace call';

CREATE TABLE IF NOT EXISTS idempotency_records (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  scope_code VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL,
  marketplace_account_id VARCHAR(36) NULL,
  request_hash VARCHAR(64) NOT NULL,
  processing_status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
  response_http_status SMALLINT NULL,
  response_json JSONB NULL,
  locked_by VARCHAR(100) NULL,
  locked_until TIMESTAMPTZ(3) NULL,
  expires_at TIMESTAMPTZ(3) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_idempotency_records_scope UNIQUE (tenant_id, scope_code, idempotency_key),
  CONSTRAINT fk_idempotency_records_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_idempotency_records_account_tenant
      FOREIGN KEY (marketplace_account_id, tenant_id)
      REFERENCES marketplace_accounts(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_idempotency_records_status
      CHECK (processing_status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);
COMMENT ON TABLE idempotency_records IS 'Prevents duplicate product/order/message mutations during client or worker retries';
CREATE INDEX idx_idempotency_records_expiry ON idempotency_records (expires_at);

-- ============================================================================
-- 8. AI Sale: RAG, understanding, generation, tools and quality gate
-- ============================================================================

CREATE TABLE IF NOT EXISTS knowledge_bases (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  knowledge_base_name VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'BUILDING',
  embedding_model VARCHAR(100) NOT NULL,
  vector_store_provider VARCHAR(50) NOT NULL DEFAULT 'EXTERNAL',
  vector_namespace VARCHAR(255) NOT NULL,
  settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_knowledge_bases_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT uq_knowledge_bases_tenant_name UNIQUE (tenant_id, knowledge_base_name),
  CONSTRAINT uq_knowledge_bases_namespace UNIQUE (vector_namespace),
  CONSTRAINT fk_knowledge_bases_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_knowledge_bases_status
      CHECK (status IN ('ACTIVE', 'BUILDING', 'ERROR', 'DISABLED'))
);
COMMENT ON TABLE knowledge_bases IS 'Tenant-isolated RAG knowledge base; embeddings may live in a vector store';

CREATE TABLE IF NOT EXISTS ai_shop_contexts (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  context_name VARCHAR(150) NOT NULL,
  mood VARCHAR(30) NOT NULL DEFAULT 'FRIENDLY',
  assistant_name VARCHAR(100) NOT NULL DEFAULT 'Trợ lý cửa hàng',
  business_description TEXT NOT NULL DEFAULT '',
  brand_voice VARCHAR(1000) NOT NULL DEFAULT '',
  response_guidelines TEXT NOT NULL DEFAULT '',
  prohibited_topics_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  default_language VARCHAR(20) NOT NULL DEFAULT 'vi',
  max_response_characters INTEGER NOT NULL DEFAULT 1200,
  default_knowledge_base_id VARCHAR(36) NULL,
  is_active BOOLEAN NOT NULL DEFAULT FALSE,
  created_by_user_id VARCHAR(36) NOT NULL,
  activated_by_user_id VARCHAR(36) NULL,
  activated_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_ai_shop_contexts_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT fk_ai_shop_contexts_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_shop_contexts_account_tenant
      FOREIGN KEY (marketplace_account_id, tenant_id)
      REFERENCES marketplace_accounts(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_ai_shop_contexts_knowledge_base_tenant
      FOREIGN KEY (default_knowledge_base_id, tenant_id)
      REFERENCES knowledge_bases(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_ai_shop_contexts_creator_tenant
      FOREIGN KEY (created_by_user_id, tenant_id)
      REFERENCES tenant_users(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT fk_ai_shop_contexts_activator_tenant
      FOREIGN KEY (activated_by_user_id, tenant_id)
      REFERENCES tenant_users(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT ck_ai_shop_contexts_mood CHECK (
      mood IN ('PROFESSIONAL', 'FRIENDLY', 'WARM', 'YOUTHFUL', 'CONCISE', 'EMPATHETIC', 'CUSTOM')
    ),
  CONSTRAINT ck_ai_shop_contexts_language
      CHECK (default_language ~ '^[A-Za-z]{2,3}([-_][A-Za-z]{2,4})?$'),
  CONSTRAINT ck_ai_shop_contexts_max_response
      CHECK (max_response_characters BETWEEN 100 AND 8000),
  CONSTRAINT ck_ai_shop_contexts_active_audit CHECK (
      (is_active = FALSE AND activated_by_user_id IS NULL AND activated_at IS NULL)
      OR (is_active = TRUE AND activated_by_user_id IS NOT NULL AND activated_at IS NOT NULL)
    )
);
COMMENT ON TABLE ai_shop_contexts IS 'Private AI response moods and instructions isolated by connected shop';
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_shop_contexts_shop_name_active_row
    ON ai_shop_contexts (marketplace_account_id, LOWER(context_name))
    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_shop_contexts_one_active_per_shop
    ON ai_shop_contexts (marketplace_account_id)
    WHERE is_active = TRUE AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ai_shop_contexts_tenant_shop
    ON ai_shop_contexts (tenant_id, marketplace_account_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS knowledge_documents (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  knowledge_base_id VARCHAR(36) NOT NULL,
  source_type VARCHAR(30) NOT NULL,
  source_reference VARCHAR(500) NULL,
  title VARCHAR(500) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  indexed_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_knowledge_documents_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT uq_knowledge_documents_hash UNIQUE (knowledge_base_id, content_hash),
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
);
COMMENT ON TABLE knowledge_documents IS 'Shop policies, FAQ, catalog and other RAG sources';
CREATE INDEX idx_knowledge_documents_status ON knowledge_documents (knowledge_base_id, status);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  knowledge_document_id VARCHAR(36) NOT NULL,
  chunk_index INTEGER NOT NULL,
  content_text TEXT NOT NULL,
  token_count INTEGER NOT NULL DEFAULT 0,
  embedding_reference VARCHAR(500) NULL,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_knowledge_chunks_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT uq_knowledge_chunks_index UNIQUE (knowledge_document_id, chunk_index),
  CONSTRAINT fk_knowledge_chunks_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_knowledge_chunks_document_tenant
      FOREIGN KEY (knowledge_document_id, tenant_id)
      REFERENCES knowledge_documents(id, tenant_id)
      ON UPDATE RESTRICT ON DELETE CASCADE
);
COMMENT ON TABLE knowledge_chunks IS 'RAG retrieval units and references to their vector embeddings';
CREATE INDEX idx_knowledge_chunks_tenant ON knowledge_chunks (tenant_id, knowledge_document_id);

CREATE TABLE IF NOT EXISTS ai_language_analyses (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  message_id VARCHAR(36) NOT NULL,
  detected_language VARCHAR(20) NOT NULL,
  intent_code VARCHAR(100) NOT NULL,
  sentiment VARCHAR(30) NULL,
  urgency VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  entities_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  confidence DECIMAL(5,4) NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_ai_language_analyses_message UNIQUE (message_id),
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
);
COMMENT ON TABLE ai_language_analyses IS 'Language, intent, sentiment, urgency and entities extracted from a customer message';
CREATE INDEX idx_ai_language_analyses_intent ON ai_language_analyses (tenant_id, intent_code, created_at);

CREATE TABLE IF NOT EXISTS ai_conversation_memories (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  memory_type VARCHAR(30) NOT NULL,
  content_text TEXT NOT NULL,
  source_message_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  confidence DECIMAL(5,4) NOT NULL,
  expires_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE ai_conversation_memories IS 'Memory is conversation-scoped and therefore never merges same-name buyers across marketplaces';
CREATE INDEX idx_ai_conversation_memories_active ON ai_conversation_memories (conversation_id, memory_type, expires_at);

CREATE TABLE IF NOT EXISTS ai_response_runs (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  trigger_message_id VARCHAR(36) NOT NULL,
  output_message_id VARCHAR(36) NULL,
  idempotency_key VARCHAR(200) NOT NULL,
  provider VARCHAR(50) NOT NULL DEFAULT 'GOOGLE',
  provider_request_id VARCHAR(200) NULL,
  model_name VARCHAR(100) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  prompt_version VARCHAR(100) NOT NULL,
  generated_text TEXT NULL,
  result_json JSONB NULL,
  safety_flags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  requires_human_review BOOLEAN NOT NULL DEFAULT TRUE,
  status VARCHAR(30) NOT NULL DEFAULT 'GENERATED',
  confidence DECIMAL(5,4) NULL,
  latency_ms INTEGER NULL,
  input_tokens INTEGER NULL,
  output_tokens INTEGER NULL,
  cached_tokens INTEGER NULL,
  estimated_cost_usd DECIMAL(18,8) NULL,
  token_usage_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  error_code VARCHAR(100) NULL,
  failure_reason TEXT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_ai_response_runs_id_tenant UNIQUE (id, tenant_id),
  CONSTRAINT uq_ai_response_runs_idempotency UNIQUE (tenant_id, idempotency_key),
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
      CHECK (requires_human_review IN (FALSE, TRUE)),
  CONSTRAINT ck_ai_response_runs_generated_text CHECK (
      status IN ('GENERATING', 'FAILED')
      OR generated_text IS NOT NULL
    ),
  CONSTRAINT ck_ai_response_runs_cost
      CHECK (estimated_cost_usd IS NULL OR estimated_cost_usd >= 0)
);
COMMENT ON TABLE ai_response_runs IS 'Natural-language response generation lifecycle before and after the send quality gate';
CREATE INDEX idx_ai_response_runs_conversation_time ON ai_response_runs (conversation_id, created_at DESC);
CREATE INDEX idx_ai_response_runs_quality_queue ON ai_response_runs (tenant_id, status, created_at);

CREATE TABLE IF NOT EXISTS ai_response_sources (
  tenant_id VARCHAR(36) NOT NULL,
  ai_response_run_id VARCHAR(36) NOT NULL,
  knowledge_chunk_id VARCHAR(36) NOT NULL,
  rank_number INTEGER NOT NULL,
  relevance_score DECIMAL(7,6) NOT NULL,
  excerpt_text TEXT NULL,
  PRIMARY KEY (ai_response_run_id, knowledge_chunk_id),
  CONSTRAINT uq_ai_response_sources_rank UNIQUE (ai_response_run_id, rank_number),
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
);
COMMENT ON TABLE ai_response_sources IS 'Exact RAG chunks used to support an AI answer';

CREATE TABLE IF NOT EXISTS ai_tool_calls (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  ai_response_run_id VARCHAR(36) NOT NULL,
  tool_name VARCHAR(100) NOT NULL,
  input_json JSONB NOT NULL,
  output_json JSONB NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
  error_code VARCHAR(100) NULL,
  started_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE ai_tool_calls IS 'Audited AI order, tracking, product and customer-history lookups';
CREATE INDEX idx_ai_tool_calls_run ON ai_tool_calls (ai_response_run_id, started_at);

CREATE TABLE IF NOT EXISTS ai_quality_checks (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  ai_response_run_id VARCHAR(36) NOT NULL,
  check_type VARCHAR(50) NOT NULL,
  passed BOOLEAN NOT NULL,
  score DECIMAL(5,4) NULL,
  findings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  checker_version VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_ai_quality_checks_type UNIQUE (ai_response_run_id, check_type),
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
  CONSTRAINT ck_ai_quality_checks_passed CHECK (passed IN (FALSE, TRUE)),
  CONSTRAINT ck_ai_quality_checks_score
      CHECK (score IS NULL OR score BETWEEN 0 AND 1)
);
COMMENT ON TABLE ai_quality_checks IS 'Pre-send quality controls; all required checks must pass before automatic send';
CREATE INDEX idx_ai_quality_checks_failed ON ai_quality_checks (tenant_id, passed, created_at);

CREATE TABLE IF NOT EXISTS ai_recommendations (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NULL,
  recommendation_type VARCHAR(50) NOT NULL,
  target_entity_type VARCHAR(30) NULL,
  target_entity_id VARCHAR(36) NULL,
  recommendation_json JSONB NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
  model_version VARCHAR(100) NOT NULL,
  decided_by_user_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at TIMESTAMPTZ(3) NULL,
  decided_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE ai_recommendations IS 'AI-proposed solutions; important actions remain subject to policy or human approval';
CREATE INDEX idx_ai_recommendations_queue ON ai_recommendations (tenant_id, status, created_at);
CREATE INDEX idx_ai_recommendations_conversation ON ai_recommendations (conversation_id, created_at DESC);

-- ============================================================================
-- 9. Human handoff, notifications, post-purchase care and AI feedback
-- ============================================================================

CREATE TABLE IF NOT EXISTS human_handoffs (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NOT NULL,
  ai_response_run_id VARCHAR(36) NULL,
  reason_code VARCHAR(100) NOT NULL,
  reason_text TEXT NULL,
  priority VARCHAR(20) NOT NULL DEFAULT 'HIGH',
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
  assigned_user_id VARCHAR(36) NULL,
  requested_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  accepted_at TIMESTAMPTZ(3) NULL,
  resolved_at TIMESTAMPTZ(3) NULL,
  resolution_note TEXT NULL,
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE human_handoffs IS 'AI-to-CSKH transfer lifecycle';
CREATE INDEX idx_human_handoffs_queue ON human_handoffs (tenant_id, status, priority, requested_at);
CREATE INDEX idx_human_handoffs_conversation ON human_handoffs (conversation_id, requested_at DESC);

CREATE TABLE IF NOT EXISTS notifications (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  recipient_user_id VARCHAR(36) NULL,
  notification_type VARCHAR(50) NOT NULL,
  channel VARCHAR(20) NOT NULL,
  title VARCHAR(255) NOT NULL,
  body_text TEXT NOT NULL,
  reference_type VARCHAR(50) NULL,
  reference_id VARCHAR(36) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
  scheduled_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at TIMESTAMPTZ(3) NULL,
  read_at TIMESTAMPTZ(3) NULL,
  failed_at TIMESTAMPTZ(3) NULL,
  error_message TEXT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE notifications IS 'In-app/email/web notifications, including urgent CSKH handoff alerts';
CREATE INDEX idx_notifications_delivery ON notifications (status, scheduled_at);
CREATE INDEX idx_notifications_user ON notifications (recipient_user_id, read_at, created_at DESC);

CREATE TABLE IF NOT EXISTS post_purchase_care_tasks (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  conversation_id VARCHAR(36) NULL,
  care_type VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
  scheduled_at TIMESTAMPTZ(3) NOT NULL,
  due_at TIMESTAMPTZ(3) NULL,
  assigned_user_id VARCHAR(36) NULL,
  ai_generated_message TEXT NULL,
  outcome_code VARCHAR(100) NULL,
  outcome_note TEXT NULL,
  completed_at TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE post_purchase_care_tasks IS 'AI-assisted after-sales follow-up tied to the actual buyer identity and order';
CREATE INDEX idx_post_purchase_care_worker ON post_purchase_care_tasks (tenant_id, status, scheduled_at);
CREATE INDEX idx_post_purchase_care_customer ON post_purchase_care_tasks (marketplace_customer_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_feedback (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  ai_response_run_id VARCHAR(36) NOT NULL,
  feedback_by_user_id VARCHAR(36) NOT NULL,
  rating SMALLINT NOT NULL,
  feedback_type VARCHAR(50) NOT NULL,
  comment_text TEXT NULL,
  corrected_text TEXT NULL,
  review_status VARCHAR(20) NOT NULL DEFAULT 'NEW',
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE ai_feedback IS 'CSKH ratings/corrections; feedback is reviewed before use for AI improvement';
CREATE INDEX idx_ai_feedback_learning_queue ON ai_feedback (tenant_id, review_status, created_at);
CREATE INDEX idx_ai_feedback_run ON ai_feedback (ai_response_run_id);

CREATE TABLE IF NOT EXISTS ai_learning_examples (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  ai_feedback_id VARCHAR(36) NOT NULL,
  input_context_json JSONB NOT NULL,
  preferred_output TEXT NOT NULL,
  rejected_output TEXT NULL,
  dataset_split VARCHAR(20) NOT NULL DEFAULT 'TRAIN',
  status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  applied_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_ai_learning_examples_feedback UNIQUE (ai_feedback_id),
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
);
COMMENT ON TABLE ai_learning_examples IS 'Human-approved learning examples; avoids uncontrolled self-learning from raw feedback';
CREATE INDEX idx_ai_learning_examples_dataset ON ai_learning_examples (tenant_id, status, dataset_split);

-- ============================================================================
-- 10. AI customer profiles, product interests and multi-label segmentation
-- ============================================================================

CREATE TABLE IF NOT EXISTS customer_ai_profiles (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  verified_customer_id VARCHAR(36) NULL,
  profile_summary TEXT NULL,
  features_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  order_count INTEGER NOT NULL DEFAULT 0,
  conversation_count INTEGER NOT NULL DEFAULT 0,
  lifetime_value DECIMAL(18,2) NOT NULL DEFAULT 0,
  model_version VARCHAR(100) NOT NULL,
  last_computed_at TIMESTAMPTZ(3) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_customer_ai_profiles_marketplace_customer UNIQUE (marketplace_customer_id),
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
);
COMMENT ON TABLE customer_ai_profiles IS 'Marketplace-buyer profile; remains separate until an explicit verified identity link exists';
CREATE INDEX idx_customer_ai_profiles_tenant_value ON customer_ai_profiles (tenant_id, lifetime_value DESC);

CREATE TABLE IF NOT EXISTS customer_product_interests (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NULL,
  external_product_id VARCHAR(200) NULL,
  interest_type VARCHAR(30) NOT NULL,
  score DECIMAL(5,4) NOT NULL,
  evidence_count INTEGER NOT NULL DEFAULT 1,
  evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  first_observed_at TIMESTAMPTZ(3) NOT NULL,
  last_observed_at TIMESTAMPTZ(3) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_customer_product_interests_internal UNIQUE (marketplace_customer_id, product_id, interest_type),
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
);
COMMENT ON TABLE customer_product_interests IS 'Explainable product interests derived from views, chat and order history';
CREATE INDEX idx_customer_product_interests_external ON customer_product_interests (marketplace_customer_id, external_product_id, interest_type);
CREATE INDEX idx_customer_product_interests_rank ON customer_product_interests (tenant_id, marketplace_customer_id, score DESC);

CREATE TABLE IF NOT EXISTS customer_segment_definitions (
  segment_code VARCHAR(50) NOT NULL,
  segment_name_vi VARCHAR(255) NOT NULL,
  description TEXT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (segment_code),
  CONSTRAINT ck_customer_segment_definitions_code CHECK (
      segment_code IN (
        'NEW', 'FIRST_TIME_BUYER', 'LOYAL', 'LIKELY_TO_REPURCHASE',
        'LIKELY_TO_CONVERT', 'CHURN_RISK', 'PRODUCT_DISSATISFIED'
      )
    ),
  CONSTRAINT ck_customer_segment_definitions_active CHECK (is_active IN (FALSE, TRUE))
);
COMMENT ON TABLE customer_segment_definitions IS 'Required customer segments; assignments are multi-label and time-bounded';

CREATE TABLE IF NOT EXISTS customer_segment_assignments (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  customer_ai_profile_id VARCHAR(36) NOT NULL,
  segment_code VARCHAR(50) NOT NULL,
  score DECIMAL(5,4) NOT NULL,
  reason_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  assignment_source VARCHAR(20) NOT NULL DEFAULT 'AI',
  model_version VARCHAR(100) NULL,
  valid_from TIMESTAMPTZ(3) NOT NULL,
  valid_until TIMESTAMPTZ(3) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_customer_segment_assignments_version UNIQUE (customer_ai_profile_id, segment_code, valid_from),
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
);
COMMENT ON TABLE customer_segment_assignments IS 'Segment history for new, first buyer, loyal, repurchase, conversion, churn and dissatisfaction';
CREATE INDEX idx_customer_segment_assignments_active ON customer_segment_assignments (tenant_id, segment_code, valid_until, score DESC);

-- ============================================================================
-- 11. Analytics, product quality alerts and scheduled email reporting
-- ============================================================================

CREATE TABLE IF NOT EXISTS analytics_daily_sales (
  tenant_id VARCHAR(36) NOT NULL,
  metric_date DATE NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  order_count INTEGER NOT NULL DEFAULT 0,
  delivered_order_count INTEGER NOT NULL DEFAULT 0,
  item_quantity INTEGER NOT NULL DEFAULT 0,
  gross_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
  discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  shipping_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
  refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  net_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
  computed_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, metric_date, marketplace_account_id, currency),
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
);
COMMENT ON TABLE analytics_daily_sales IS 'Daily marketplace revenue facts; sum by ISO week, month or year';
CREATE INDEX idx_analytics_daily_sales_account ON analytics_daily_sales (marketplace_account_id, metric_date);

CREATE TABLE IF NOT EXISTS analytics_daily_product_performance (
  tenant_id VARCHAR(36) NOT NULL,
  metric_date DATE NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  order_count INTEGER NOT NULL DEFAULT 0,
  purchased_quantity INTEGER NOT NULL DEFAULT 0,
  gross_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
  cancelled_order_count INTEGER NOT NULL DEFAULT 0,
  returned_order_count INTEGER NOT NULL DEFAULT 0,
  failed_order_count INTEGER NOT NULL DEFAULT 0,
  cancel_rate DECIMAL(7,6) NOT NULL DEFAULT 0,
  return_rate DECIMAL(7,6) NOT NULL DEFAULT 0,
  computed_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (
      tenant_id, metric_date, marketplace_account_id, product_id, currency
    ),
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
);
COMMENT ON TABLE analytics_daily_product_performance IS 'Daily product facts for best/worst sellers and cancel/return trend detection';
CREATE INDEX idx_analytics_product_ranking ON analytics_daily_product_performance (tenant_id, metric_date, purchased_quantity DESC);
CREATE INDEX idx_analytics_product_quality ON analytics_daily_product_performance (tenant_id, metric_date, cancel_rate DESC, return_rate DESC);

CREATE TABLE IF NOT EXISTS analytics_alert_rules (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  rule_code VARCHAR(100) NOT NULL,
  alert_type VARCHAR(50) NOT NULL,
  lookback_days SMALLINT NOT NULL DEFAULT 7,
  minimum_order_count INTEGER NOT NULL DEFAULT 5,
  threshold_value DECIMAL(18,6) NOT NULL,
  comparison_operator VARCHAR(10) NOT NULL DEFAULT 'GTE',
  severity VARCHAR(20) NOT NULL DEFAULT 'WARNING',
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  recipient_role_codes_json JSONB NOT NULL DEFAULT '["TENANT_MANAGER"]'::jsonb,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_analytics_alert_rules_code UNIQUE (tenant_id, rule_code),
  CONSTRAINT fk_analytics_alert_rules_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_analytics_alert_rules_type
      CHECK (alert_type IN ('HIGH_CANCEL_RATE', 'HIGH_RETURN_RATE', 'CANCEL_RATE_SPIKE', 'RETURN_RATE_SPIKE')),
  CONSTRAINT ck_analytics_alert_rules_operator
      CHECK (comparison_operator IN ('GT', 'GTE', 'LT', 'LTE')),
  CONSTRAINT ck_analytics_alert_rules_severity
      CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  CONSTRAINT ck_analytics_alert_rules_active CHECK (is_active IN (FALSE, TRUE))
);
COMMENT ON TABLE analytics_alert_rules IS 'Tenant-configurable product cancellation/return alert thresholds';

CREATE TABLE IF NOT EXISTS analytics_alerts (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  alert_rule_id VARCHAR(36) NULL,
  alert_type VARCHAR(50) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  entity_type VARCHAR(30) NOT NULL,
  entity_id VARCHAR(36) NOT NULL,
  metric_value DECIMAL(18,6) NULL,
  previous_metric_value DECIMAL(18,6) NULL,
  threshold_value DECIMAL(18,6) NULL,
  details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  detected_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  acknowledged_by_user_id VARCHAR(36) NULL,
  acknowledged_at TIMESTAMPTZ(3) NULL,
  resolved_at TIMESTAMPTZ(3) NULL,
  PRIMARY KEY (id),
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
);
COMMENT ON TABLE analytics_alerts IS 'Detected product quality alerts for managers';
CREATE INDEX idx_analytics_alerts_queue ON analytics_alerts (tenant_id, status, severity, detected_at DESC);
CREATE INDEX idx_analytics_alerts_entity ON analytics_alerts (tenant_id, entity_type, entity_id, detected_at DESC);

CREATE TABLE IF NOT EXISTS report_schedules (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  report_type VARCHAR(50) NOT NULL DEFAULT 'DAILY_MANAGEMENT',
  schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  send_time TIME NOT NULL DEFAULT '08:00:00',
  recipient_emails_json JSONB NOT NULL,
  include_marketplace_breakdown BOOLEAN NOT NULL DEFAULT TRUE,
  include_product_ranking BOOLEAN NOT NULL DEFAULT TRUE,
  include_alerts BOOLEAN NOT NULL DEFAULT TRUE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  next_run_at TIMESTAMPTZ(3) NULL,
  created_by_user_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_report_schedules_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_report_schedules_user
      FOREIGN KEY (created_by_user_id) REFERENCES tenant_users(id)
      ON UPDATE RESTRICT ON DELETE SET NULL,
  CONSTRAINT ck_report_schedules_type
      CHECK (report_type IN ('DAILY_MANAGEMENT')),
  CONSTRAINT ck_report_schedules_flags CHECK (
      include_marketplace_breakdown IN (FALSE, TRUE)
      AND include_product_ranking IN (FALSE, TRUE)
      AND include_alerts IN (FALSE, TRUE)
      AND is_active IN (FALSE, TRUE)
    )
);
COMMENT ON TABLE report_schedules IS 'Daily manager email configuration';
CREATE INDEX idx_report_schedules_worker ON report_schedules (is_active, next_run_at);

CREATE TABLE IF NOT EXISTS report_deliveries (
  id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  report_schedule_id VARCHAR(36) NOT NULL,
  report_date DATE NOT NULL,
  period_start TIMESTAMPTZ(3) NOT NULL,
  period_end TIMESTAMPTZ(3) NOT NULL,
  recipient_emails_json JSONB NOT NULL,
  subject_text VARCHAR(500) NOT NULL,
  report_payload_json JSONB NOT NULL,
  attachment_storage_key VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
  provider_message_id VARCHAR(200) NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMPTZ(3) NULL,
  locked_by VARCHAR(100) NULL,
  locked_until TIMESTAMPTZ(3) NULL,
  sent_at TIMESTAMPTZ(3) NULL,
  last_error TEXT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_report_deliveries_schedule_date UNIQUE (report_schedule_id, report_date),
  CONSTRAINT fk_report_deliveries_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT fk_report_deliveries_schedule
      FOREIGN KEY (report_schedule_id) REFERENCES report_schedules(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_report_deliveries_status
      CHECK (status IN ('QUEUED', 'GENERATING', 'SENDING', 'SENT', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_report_deliveries_period CHECK (period_end > period_start)
);
COMMENT ON TABLE report_deliveries IS 'Idempotent generation and email-delivery history for daily management reports';
CREATE INDEX idx_report_deliveries_worker ON report_deliveries (status, next_retry_at, locked_until, created_at);


-- Keep updated_at consistent for every writer (Spring, Chat, AI and workers).
CREATE OR REPLACE FUNCTION set_updated_at_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP(3);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
  target_table RECORD;
BEGIN
  FOR target_table IN
    SELECT table_name
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND column_name = 'updated_at'
  LOOP
    EXECUTE format(
      'CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION set_updated_at_timestamp()',
      'trg_' || target_table.table_name || '_updated_at',
      target_table.table_name
    );
  END LOOP;
END;
$$;

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
)
ON CONFLICT (id) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  status = EXCLUDED.status ;

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
  jsonb_build_object('marketplace_accounts', 2, 'tenant_users', 10),
  jsonb_build_object('ai_sale', TRUE, 'analytics', TRUE, 'daily_email', TRUE),
  'ACTIVE'
)
ON CONFLICT (id) DO UPDATE SET
  plan_name = EXCLUDED.plan_name,
  limits_json = EXCLUDED.limits_json,
  features_json = EXCLUDED.features_json,
  status = EXCLUDED.status ;

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
  jsonb_build_object('first_login_password_change_required', TRUE),
  '00000000-0000-0000-0000-000000000001',
  '2026-07-27 00:00:00.000'
)
ON CONFLICT (id) DO UPDATE SET
  tenant_name = EXCLUDED.tenant_name,
  status = EXCLUDED.status,
  settings_json = EXCLUDED.settings_json ;

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
)
ON CONFLICT (id) DO UPDATE SET
  status = EXCLUDED.status,
  current_period_ends_at = EXCLUDED.current_period_ends_at ;

INSERT INTO data_protection_policies (
  id, tenant_id, data_category, retention_days, encrypt_at_rest,
  redact_in_logs, allow_ai_processing, purge_enabled, policy_version
) VALUES
  ('61000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'CUSTOMER_CONTACT', 730, TRUE, TRUE, FALSE, TRUE, 'v1'),
  ('61000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', 'ORDER_ADDRESS', 730, TRUE, TRUE, FALSE, TRUE, 'v1'),
  ('61000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001', 'MESSAGE_CONTENT', 365, TRUE, TRUE, TRUE, TRUE, 'v1'),
  ('61000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000001', 'RAW_PAYLOAD', 90, TRUE, TRUE, FALSE, TRUE, 'v1'),
  ('61000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000001', 'WEBHOOK_PAYLOAD', 90, TRUE, TRUE, FALSE, TRUE, 'v1'),
  ('61000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000001', 'API_AUDIT', 180, TRUE, TRUE, FALSE, TRUE, 'v1'),
  ('61000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000001', 'AI_CONTEXT', 180, TRUE, TRUE, TRUE, TRUE, 'v1'),
  ('61000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000001', 'AI_OUTPUT', 365, TRUE, TRUE, TRUE, TRUE, 'v1')
ON CONFLICT (id) DO UPDATE SET
  retention_days = EXCLUDED.retention_days,
  encrypt_at_rest = EXCLUDED.encrypt_at_rest,
  redact_in_logs = EXCLUDED.redact_in_logs,
  allow_ai_processing = EXCLUDED.allow_ai_processing,
  purge_enabled = EXCLUDED.purge_enabled,
  policy_version = EXCLUDED.policy_version ;

INSERT INTO tenant_users (
  id, tenant_id, email, display_name, status, provisioned_by_admin_id
) VALUES (
  '30000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000001',
  'manager@example.test',
  'Quản lý Demo',
  'ACTIVE',
  '00000000-0000-0000-0000-000000000001'
)
ON CONFLICT (id) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  status = EXCLUDED.status ;

INSERT INTO tenant_user_credentials (
  tenant_user_id, password_hash, password_algorithm, must_change_password,
  credential_version
) VALUES
  ('30000000-0000-0000-0000-000000000001', '$argon2id$v=19$m=65536,t=3,p=1$REPLACE_ME$TEMPORARY_PASSWORD_HASH', 'ARGON2ID', TRUE, 1)
ON CONFLICT (tenant_user_id) DO UPDATE SET
  must_change_password = EXCLUDED.must_change_password ;

INSERT INTO roles (
  id, tenant_id, role_code, role_name, description, is_system
) VALUES
  ('40000000-0000-0000-0000-000000000001', NULL, 'TENANT_MANAGER', 'Quản lý tenant', 'Quản lý kết nối, sản phẩm, đơn hàng, CSKH, AI và báo cáo trong tenant', TRUE),
  ('40000000-0000-0000-0000-000000000002', NULL, 'CS_AGENT', 'Nhân viên CSKH', 'Xử lý Unified Inbox, handoff và feedback AI', TRUE)
ON CONFLICT (id) DO UPDATE SET
  role_name = EXCLUDED.role_name,
  description = EXCLUDED.description ;

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
ON CONFLICT (id) DO UPDATE SET
  permission_name = EXCLUDED.permission_name,
  resource_code = EXCLUDED.resource_code,
  action_code = EXCLUDED.action_code ;

INSERT INTO role_permissions (role_id, permission_id)
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
)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT
  '40000000-0000-0000-0000-000000000002',
  id
FROM permissions
WHERE permission_code IN (
  'PRODUCT.READ', 'ORDER.READ',
  'CHAT.READ', 'CHAT.REPLY', 'CHAT.ASSIGN',
  'AI.SUGGEST', 'AI.APPROVE'
)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO tenant_user_roles (
  tenant_user_id, tenant_id, role_id, role_scope_key, assigned_by_user_id
) VALUES (
  '30000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000001',
  '40000000-0000-0000-0000-000000000001',
  '00000000-0000-0000-0000-000000000000',
  NULL
)
ON CONFLICT (tenant_user_id, role_id) DO UPDATE SET
  tenant_id = EXCLUDED.tenant_id,
  role_scope_key = EXCLUDED.role_scope_key,
  role_id = EXCLUDED.role_id ;

INSERT INTO marketplaces (
  id, marketplace_code, marketplace_name, adapter_code, mock_base_url,
  is_active, capabilities_json
) VALUES
  ('50000000-0000-0000-0000-000000000001', 'TIKTOK_SHOP', 'TikTok Shop', 'TIKTOK_SHOP_MOCK_V1', 'http://localhost:4011', TRUE, jsonb_build_object('chat', TRUE, 'order', TRUE, 'product', TRUE, 'webhook', TRUE)),
  ('50000000-0000-0000-0000-000000000002', 'LAZADA', 'Lazada', 'LAZADA_MOCK_V1', 'http://localhost:4012/rest', TRUE, jsonb_build_object('chat', TRUE, 'order', TRUE, 'product', TRUE, 'push', TRUE))
ON CONFLICT (id) DO UPDATE SET
  marketplace_name = EXCLUDED.marketplace_name,
  adapter_code = EXCLUDED.adapter_code,
  mock_base_url = EXCLUDED.mock_base_url,
  is_active = EXCLUDED.is_active,
  capabilities_json = EXCLUDED.capabilities_json ;

INSERT INTO customer_segment_definitions (
  segment_code, segment_name_vi, description, is_active
) VALUES
  ('NEW', 'Khách mới', 'Mới xuất hiện, chưa có đơn hoàn tất', TRUE),
  ('FIRST_TIME_BUYER', 'Khách mua lần đầu', 'Có đúng một lần mua đầu tiên', TRUE),
  ('LOYAL', 'Khách thân thiết', 'Mua lại và tương tác tích cực nhiều lần', TRUE),
  ('LIKELY_TO_REPURCHASE', 'Có khả năng mua lại', 'Tín hiệu chu kỳ hoặc nhu cầu mua lại cao', TRUE),
  ('LIKELY_TO_CONVERT', 'Có khả năng chốt đơn', 'Tín hiệu hỏi mua, xem, giỏ hàng và ý định cao', TRUE),
  ('CHURN_RISK', 'Có nguy cơ rời bỏ', 'Tần suất mua/tương tác giảm hoặc có trải nghiệm xấu', TRUE),
  ('PRODUCT_DISSATISFIED', 'Không hài lòng về sản phẩm', 'Phản hồi, trả hàng hoặc cảm xúc tiêu cực về sản phẩm', TRUE)
ON CONFLICT (segment_code) DO UPDATE SET
  segment_name_vi = EXCLUDED.segment_name_vi,
  description = EXCLUDED.description,
  is_active = EXCLUDED.is_active ;

INSERT INTO analytics_alert_rules (
  id, tenant_id, rule_code, alert_type, lookback_days,
  minimum_order_count, threshold_value, comparison_operator, severity, is_active
) VALUES
  ('60000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'PRODUCT_CANCEL_RATE_7D', 'HIGH_CANCEL_RATE', 7, 5, 0.200000, 'GTE', 'WARNING', TRUE),
  ('60000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', 'PRODUCT_RETURN_RATE_30D', 'HIGH_RETURN_RATE', 30, 5, 0.100000, 'GTE', 'WARNING', TRUE)
ON CONFLICT (id) DO UPDATE SET
  lookback_days = EXCLUDED.lookback_days,
  minimum_order_count = EXCLUDED.minimum_order_count,
  threshold_value = EXCLUDED.threshold_value,
  severity = EXCLUDED.severity,
  is_active = EXCLUDED.is_active ;

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
