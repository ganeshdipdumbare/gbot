-- Model providers a person connects to their account: any OpenAI-compatible
-- server, Anthropic, or Gemini. Keys are sealed with the server's secret key,
-- and the model prefix is generated so it can never collide across accounts.
CREATE TABLE model_providers (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  kind       TEXT NOT NULL CHECK (kind IN ('openai','anthropic','gemini')),
  prefix     TEXT NOT NULL UNIQUE,
  base_url   TEXT NOT NULL DEFAULT '',
  api_key    BYTEA,
  local      BOOLEAN NOT NULL DEFAULT false,
  discovered JSONB NOT NULL DEFAULT '[]',
  manual     JSONB NOT NULL DEFAULT '[]',
  enabled    BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX model_providers_user ON model_providers(user_id);
