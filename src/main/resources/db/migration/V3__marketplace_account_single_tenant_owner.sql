-- A seller/shop on one marketplace can belong to only one tenant.
--
-- This ALTER is atomic in MySQL. If historical cross-tenant duplicates exist,
-- it fails without deleting or selecting an owner automatically. Inspect them
-- before restarting the application with:
--
-- SELECT marketplace_id, external_account_id,
--        COUNT(*) AS owner_count,
--        GROUP_CONCAT(tenant_id ORDER BY created_at) AS tenant_ids
-- FROM marketplace_accounts
-- GROUP BY marketplace_id, external_account_id
-- HAVING COUNT(*) > 1;

ALTER TABLE marketplace_accounts
  DROP INDEX uq_marketplace_accounts_scope,
  ADD UNIQUE KEY uq_marketplace_accounts_external_owner
    (marketplace_id, external_account_id);
