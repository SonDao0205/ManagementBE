CREATE TABLE IF NOT EXISTS weekly_analytics_email_deliveries (
  tenant_id VARCHAR(36) NOT NULL,
  week_start DATE NOT NULL,
  recipient_email VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SENDING',
  sent_at TIMESTAMPTZ(3) NULL,
  last_error TEXT NULL,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, week_start, recipient_email),
  CONSTRAINT fk_weekly_analytics_email_tenant
      FOREIGN KEY (tenant_id) REFERENCES tenants(id)
      ON UPDATE RESTRICT ON DELETE CASCADE,
  CONSTRAINT ck_weekly_analytics_email_status
      CHECK (status IN ('SENDING', 'SENT', 'FAILED'))
);

COMMENT ON TABLE weekly_analytics_email_deliveries IS
    'Idempotent weekly financial analytics email delivery history per tenant recipient';
CREATE INDEX idx_weekly_analytics_email_status
    ON weekly_analytics_email_deliveries (status, week_start, updated_at);
