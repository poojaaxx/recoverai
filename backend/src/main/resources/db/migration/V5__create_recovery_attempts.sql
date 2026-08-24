CREATE TABLE recovery_attempts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id   UUID          NOT NULL,
    action           VARCHAR(30)   NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    attempt_number   INTEGER       NOT NULL,
    reason           TEXT,
    result           TEXT,
    amount_recovered NUMERIC(14,2),
    executed_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_recovery_attempts_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE,
    CONSTRAINT chk_recovery_attempts_attempt_number_positive CHECK (attempt_number > 0),
    CONSTRAINT chk_recovery_attempts_action CHECK (action IN (
        'RETRY_PAYMENT', 'CREATE_PAYMENT_LINK', 'SEND_RECOVERY_REMINDER', 'ESCALATE', 'STOP'
    )),
    CONSTRAINT chk_recovery_attempts_status CHECK (status IN (
        'PENDING', 'SUCCESS', 'FAILED', 'BLOCKED', 'ESCALATED'
    )),
    CONSTRAINT chk_recovery_attempts_amount_recovered_nonneg CHECK (amount_recovered IS NULL OR amount_recovered >= 0)
);
