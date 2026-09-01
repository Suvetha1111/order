-- ============================================================
-- OrderFlow Initial Schema
-- ============================================================

-- ── Users & Roles ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS roles (
    id              UUID PRIMARY KEY,
    name            VARCHAR(50)  NOT NULL UNIQUE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- Seed default roles
INSERT INTO roles (id, name, created_at, updated_at)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'CUSTOMER', NOW(), NOW()),
    ('a0000000-0000-0000-0000-000000000002', 'ADMIN', NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- ── Products & Inventory ─────────────────────────────────────

CREATE TABLE IF NOT EXISTS products (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    price           NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    sku             VARCHAR(100)  NOT NULL UNIQUE,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS inventory (
    id              UUID PRIMARY KEY,
    product_id      UUID          NOT NULL UNIQUE REFERENCES products(id),
    quantity        INTEGER       NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    reserved        INTEGER       NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reserved_lte_quantity CHECK (reserved <= quantity)
);

-- ── Orders ───────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS orders (
    id              UUID PRIMARY KEY,
    user_id         UUID          NOT NULL REFERENCES users(id),
    status          VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    total_amount    NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    idempotency_key VARCHAR(255)  UNIQUE,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);

CREATE TABLE IF NOT EXISTS order_items (
    id              UUID PRIMARY KEY,
    order_id        UUID          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id      UUID          NOT NULL REFERENCES products(id),
    quantity        INTEGER       NOT NULL CHECK (quantity > 0),
    unit_price      NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);

-- ── Transactional Outbox ─────────────────────────────────────
-- Events are written here inside the same transaction as the
-- business operation, then published to Kafka by a poller.

CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100)  NOT NULL,
    aggregate_id    UUID          NOT NULL,
    event_type      VARCHAR(100)  NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    published       BOOLEAN       NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox_events(published, created_at)
    WHERE published = FALSE;

-- ── Idempotent Event Processing ──────────────────────────────
-- Tracks which events have been processed by each consumer
-- to guarantee exactly-once semantics on top of at-least-once delivery.

CREATE TABLE IF NOT EXISTS processed_events (
    event_id        UUID         NOT NULL,
    consumer_group  VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_group)
);
