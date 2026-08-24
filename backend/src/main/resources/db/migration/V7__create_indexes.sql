-- PostgreSQL does not automatically index foreign key columns (unlike
-- primary keys), so FK columns that later phases will filter/join on get
-- explicit indexes here. Kept deliberately minimal — only what Phase 2's
-- known query patterns (transaction lookups, dashboard filtering by
-- merchant/status/date, audit trail reconstruction) actually need.

CREATE INDEX idx_customers_merchant_id ON customers (merchant_id);

CREATE INDEX idx_transactions_merchant_id ON transactions (merchant_id);
CREATE INDEX idx_transactions_customer_id ON transactions (customer_id);
CREATE INDEX idx_transactions_status ON transactions (status);
CREATE INDEX idx_transactions_created_at ON transactions (created_at);

CREATE INDEX idx_revenue_risks_transaction_id ON revenue_risks (transaction_id);

CREATE INDEX idx_recovery_attempts_transaction_id ON recovery_attempts (transaction_id);

CREATE INDEX idx_audit_logs_transaction_id ON audit_logs (transaction_id);
CREATE INDEX idx_audit_logs_event_type ON audit_logs (event_type);
CREATE INDEX idx_audit_logs_timestamp ON audit_logs ("timestamp");
