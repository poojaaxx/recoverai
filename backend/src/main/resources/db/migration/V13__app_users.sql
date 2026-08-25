-- Production readiness phase: application-level authentication/authorization.
-- A lightweight identity store - not a full identity platform - backing a
-- stateless JWT login flow (see com.recoverai.security). Passwords are
-- stored only as bcrypt hashes, never plaintext.
CREATE TABLE app_users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(30)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_app_users_username UNIQUE (username),
    CONSTRAINT chk_app_users_role CHECK (role IN ('MERCHANT_ADMIN', 'OPERATOR'))
);
