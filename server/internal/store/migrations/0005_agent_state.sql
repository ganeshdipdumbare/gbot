-- Model conversations and long-term memory for kritrim.

CREATE TABLE IF NOT EXISTS agent_sessions (
  id         TEXT PRIMARY KEY,
  version    BIGINT NOT NULL DEFAULT 0,
  state      JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_memory (
  ns         TEXT NOT NULL,
  key        TEXT NOT NULL,
  value      TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (ns, key)
);
CREATE INDEX IF NOT EXISTS agent_memory_recent ON agent_memory(ns, updated_at DESC);
