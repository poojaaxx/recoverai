CREATE TABLE transactions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_transaction_id VARCHAR(100)  NOT NULL,
    merchant_id              UUID          NOT NULL,
    customer_id              UUID          NOT NULL,
    amount                   NUMERIC(14,2) NOT NULL,
    currency                 VARCHAR(3)    NOT NULL DEFAULT 'INR',
    status                   VARCHAR(20)   NOT NULL,
    payment_method           VARCHAR(20),
    failure_code             VARCHAR(50),
    failure_reason           TEXT,
    attempt_count            INTEGER       NOT NULL DEFAULT 1,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_transactions_external_id UNIQUE (external_transaction_id),
    CONSTRAINT fk_transactions_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id) ON DELETE CASCADE,
    CONSTRAINT fk_transactions_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE CASCADE,
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0),
    -- 0 is valid: an ABANDONED checkout means the customer never completed
    -- a payment attempt, so there is nothing to count.
    CONSTRAINT chk_transactions_attempt_count_nonneg CHECK (attempt_count >= 0),
    CONSTRAINT chk_transactions_status CHECK (status IN (
        'SUCCESS', 'FAILED', 'PENDING', 'ABANDONED', 'RECOVERED', 'ESCALATED', 'STOPPED'
    )),
    CONSTRAINT chk_transactions_payment_method CHECK (payment_method IS NULL OR payment_method IN (
        'CARD', 'UPI', 'NETBANKING', 'WALLET', 'EMI'
    ))
);
