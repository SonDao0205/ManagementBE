-- One satisfaction follow-up per completed marketplace order.
CREATE UNIQUE INDEX IF NOT EXISTS uq_post_purchase_care_order_type
  ON post_purchase_care_tasks (tenant_id, order_id, care_type);
