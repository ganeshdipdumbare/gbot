-- Account secrets. The value is sealed with the server key and is never
-- returned by the API or put in a model prompt. A secret is bound to the
-- sites where the bot may type it.
CREATE TABLE vault_secrets (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  origins    JSONB NOT NULL DEFAULT '[]'::jsonb,
  value      BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, name)
);
CREATE INDEX vault_user ON vault_secrets(user_id, name);
