-- Email now identifies exactly one tenant login across the whole platform.
-- Resolve any existing duplicate tenant-user emails before applying this migration.
ALTER TABLE tenant_users
  ADD UNIQUE KEY uq_tenant_users_email (email);
