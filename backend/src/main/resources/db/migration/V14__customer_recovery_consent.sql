-- Phase 14: minimal customer consent / do-not-contact compliance boundary.
-- Defaults to true (contact allowed) so existing seeded/live customers are
-- unaffected until explicitly opted out.
ALTER TABLE customers ADD COLUMN recovery_contact_allowed BOOLEAN NOT NULL DEFAULT true;
