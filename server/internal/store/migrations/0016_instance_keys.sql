-- One row per instance secret: a check value derived from the sealing key.
-- It lets startup refuse a key that does not match the stored secrets,
-- instead of letting every sealed value fail to open later.
CREATE TABLE instance_keys (
    name        TEXT PRIMARY KEY,
    check_value BYTEA NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
