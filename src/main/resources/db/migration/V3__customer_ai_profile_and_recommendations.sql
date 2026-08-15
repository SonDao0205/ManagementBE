-- Long-lived, tenant-isolated customer memory used by ChatBE autopilot.
ALTER TABLE customer_ai_profiles
  ADD COLUMN IF NOT EXISTS profile_status VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
  ADD COLUMN IF NOT EXISTS profile_version INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS message_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_summarized_message_id VARCHAR(36) NULL,
  ADD COLUMN IF NOT EXISTS last_summarized_at TIMESTAMPTZ(3) NULL,
  ADD COLUMN IF NOT EXISTS summary_version VARCHAR(100) NOT NULL DEFAULT 'customer-profile-v1',
  ADD COLUMN IF NOT EXISTS lead_priority VARCHAR(30) NULL,
  ADD COLUMN IF NOT EXISTS lead_priority_score DECIMAL(5,4) NULL,
  ADD COLUMN IF NOT EXISTS lead_priority_reason TEXT NULL,
  ADD COLUMN IF NOT EXISTS lead_priority_evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS lead_priority_computed_at TIMESTAMPTZ(3) NULL,
  ADD COLUMN IF NOT EXISTS lead_priority_model_version VARCHAR(100) NULL,
  ADD COLUMN IF NOT EXISTS lead_priority_override BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS lead_priority_override_until TIMESTAMPTZ(3) NULL,
  ADD COLUMN IF NOT EXISTS lead_priority_override_reason TEXT NULL;

ALTER TABLE customer_ai_profiles
  DROP CONSTRAINT IF EXISTS ck_customer_ai_profiles_profile_status;
ALTER TABLE customer_ai_profiles
  ADD CONSTRAINT ck_customer_ai_profiles_profile_status
  CHECK (profile_status IN ('EMPTY', 'PENDING', 'READY', 'FAILED'));

ALTER TABLE customer_ai_profiles
  DROP CONSTRAINT IF EXISTS ck_customer_ai_profiles_lead_priority;
ALTER TABLE customer_ai_profiles
  ADD CONSTRAINT ck_customer_ai_profiles_lead_priority
  CHECK (lead_priority IS NULL OR lead_priority IN (
    'HOT_LEAD', 'WARM_LEAD', 'COLD_LEAD', 'EXISTING_PRIORITY'
  ));

ALTER TABLE customer_ai_profiles
  DROP CONSTRAINT IF EXISTS ck_customer_ai_profiles_profile_metrics;
ALTER TABLE customer_ai_profiles
  ADD CONSTRAINT ck_customer_ai_profiles_profile_metrics CHECK (
    profile_version >= 0 AND message_count >= 0
    AND (lead_priority_score IS NULL OR lead_priority_score BETWEEN 0 AND 1)
  );

ALTER TABLE customer_ai_profiles
  DROP CONSTRAINT IF EXISTS fk_customer_ai_profiles_last_message;
ALTER TABLE customer_ai_profiles
  ADD CONSTRAINT fk_customer_ai_profiles_last_message
  FOREIGN KEY (last_summarized_message_id) REFERENCES messages(id)
  ON UPDATE RESTRICT ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS customer_ai_profile_revisions (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  profile_version INTEGER NOT NULL,
  previous_profile_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  new_profile_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  source VARCHAR(20) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  changed_by_user_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uq_customer_ai_profile_revision UNIQUE (
    marketplace_customer_id, profile_version
  ),
  CONSTRAINT fk_customer_ai_profile_revision_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
  CONSTRAINT fk_customer_ai_profile_revision_customer
    FOREIGN KEY (marketplace_customer_id) REFERENCES marketplace_customers(id)
    ON DELETE CASCADE,
  CONSTRAINT ck_customer_ai_profile_revision_source
    CHECK (source IN ('AI', 'RULE_ENGINE', 'STAFF', 'ORDER_EVENT'))
);

CREATE INDEX IF NOT EXISTS idx_customer_ai_profile_revisions_customer
  ON customer_ai_profile_revisions (marketplace_customer_id, profile_version DESC);

CREATE TABLE IF NOT EXISTS customer_lead_priority_history (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  previous_priority VARCHAR(30) NULL,
  new_priority VARCHAR(30) NOT NULL,
  score DECIMAL(5,4) NOT NULL,
  reason_text TEXT NOT NULL,
  evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  source VARCHAR(20) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  changed_by_user_id VARCHAR(36) NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_customer_lead_priority_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
  CONSTRAINT fk_customer_lead_priority_customer
    FOREIGN KEY (marketplace_customer_id) REFERENCES marketplace_customers(id)
    ON DELETE CASCADE,
  CONSTRAINT ck_customer_lead_priority_previous CHECK (
    previous_priority IS NULL OR previous_priority IN (
      'HOT_LEAD', 'WARM_LEAD', 'COLD_LEAD', 'EXISTING_PRIORITY'
    )
  ),
  CONSTRAINT ck_customer_lead_priority_new CHECK (
    new_priority IN ('HOT_LEAD', 'WARM_LEAD', 'COLD_LEAD', 'EXISTING_PRIORITY')
  ),
  CONSTRAINT ck_customer_lead_priority_score CHECK (score BETWEEN 0 AND 1),
  CONSTRAINT ck_customer_lead_priority_source CHECK (
    source IN ('AI', 'RULE_ENGINE', 'STAFF', 'ORDER_EVENT')
  )
);

CREATE INDEX IF NOT EXISTS idx_customer_lead_priority_history_customer
  ON customer_lead_priority_history (marketplace_customer_id, created_at DESC);

CREATE TABLE IF NOT EXISTS customer_ai_product_recommendations (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  marketplace_customer_id VARCHAR(36) NOT NULL,
  marketplace_account_id VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NOT NULL,
  variant_id VARCHAR(36) NULL,
  recommendation_type VARCHAR(20) NOT NULL,
  score DECIMAL(5,4) NOT NULL,
  reason_text TEXT NOT NULL,
  evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  lead_priority_at_generation VARCHAR(30) NULL,
  profile_version INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  expires_at TIMESTAMPTZ(3) NULL,
  generated_at TIMESTAMPTZ(3) NOT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uq_customer_ai_product_recommendation UNIQUE (
    marketplace_customer_id, product_id, recommendation_type, profile_version
  ),
  CONSTRAINT fk_customer_ai_recommendation_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
  CONSTRAINT fk_customer_ai_recommendation_customer
    FOREIGN KEY (marketplace_customer_id) REFERENCES marketplace_customers(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_customer_ai_recommendation_account
    FOREIGN KEY (marketplace_account_id, tenant_id)
    REFERENCES marketplace_accounts(id, tenant_id) ON DELETE CASCADE,
  CONSTRAINT fk_customer_ai_recommendation_product
    FOREIGN KEY (product_id, tenant_id) REFERENCES products(id, tenant_id)
    ON DELETE CASCADE,
  CONSTRAINT fk_customer_ai_recommendation_variant
    FOREIGN KEY (variant_id, tenant_id) REFERENCES product_variants(id, tenant_id)
    ON DELETE RESTRICT,
  CONSTRAINT ck_customer_ai_recommendation_type CHECK (
    recommendation_type IN ('UPSELL', 'CROSS_SELL', 'REPURCHASE', 'ALTERNATIVE')
  ),
  CONSTRAINT ck_customer_ai_recommendation_status CHECK (
    status IN ('ACTIVE', 'DISMISSED', 'CONVERTED', 'EXPIRED', 'OUT_OF_STOCK')
  ),
  CONSTRAINT ck_customer_ai_recommendation_score CHECK (score BETWEEN 0 AND 1)
);

CREATE INDEX IF NOT EXISTS idx_customer_ai_recommendations_active
  ON customer_ai_product_recommendations
  (tenant_id, marketplace_customer_id, status, score DESC);

ALTER TABLE ai_response_runs
  ADD COLUMN IF NOT EXISTS customer_profile_version_used INTEGER NULL,
  ADD COLUMN IF NOT EXISTS lead_priority_used VARCHAR(30) NULL,
  ADD COLUMN IF NOT EXISTS response_strategy_used VARCHAR(50) NULL,
  ADD COLUMN IF NOT EXISTS profile_compliance_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ai_response_runs
  DROP CONSTRAINT IF EXISTS ck_ai_response_runs_lead_priority;
ALTER TABLE ai_response_runs
  ADD CONSTRAINT ck_ai_response_runs_lead_priority CHECK (
    lead_priority_used IS NULL OR lead_priority_used IN (
      'HOT_LEAD', 'WARM_LEAD', 'COLD_LEAD', 'EXISTING_PRIORITY'
    )
  );
