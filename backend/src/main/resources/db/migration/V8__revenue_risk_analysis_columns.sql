-- Phase 3 (Revenue Risk Engine) additions to the Phase 2 revenue_risks
-- table: structured risk_level/factors output for downstream consumption
-- (dashboard, and later the AI agent), and a uniqueness constraint so
-- re-analyzing a transaction updates its existing risk record instead of
-- accumulating duplicates.

ALTER TABLE revenue_risks
    ADD COLUMN risk_level VARCHAR(20),
    ADD COLUMN factors JSONB;

ALTER TABLE revenue_risks
    ADD CONSTRAINT chk_revenue_risks_risk_level CHECK (risk_level IS NULL OR risk_level IN (
        'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'
    ));

-- Superseded by the unique constraint below, which Postgres backs with
-- its own index on the same column.
DROP INDEX IF EXISTS idx_revenue_risks_transaction_id;

ALTER TABLE revenue_risks
    ADD CONSTRAINT uq_revenue_risks_transaction_id UNIQUE (transaction_id);
