CREATE TABLE audit_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID         NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    actor          VARCHAR(30)  NOT NULL,
    decision       VARCHAR(30),
    reason         TEXT,
    metadata       JSONB,
    "timestamp"    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_audit_logs_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE
);
