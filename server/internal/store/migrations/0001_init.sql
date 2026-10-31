CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email         TEXT NOT NULL UNIQUE,
  name          TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  default_model TEXT NOT NULL DEFAULT '',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Session tokens are random 32 bytes; only their sha256 is stored.
CREATE TABLE sessions (
  token_hash BYTEA PRIMARY KEY,
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user ON sessions(user_id);

-- A space is the authorization boundary: bots, chats, memory and
-- computers never cross it.
CREATE TABLE spaces (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE space_members (
  space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
  user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role     TEXT NOT NULL DEFAULT 'owner',
  PRIMARY KEY (space_id, user_id)
);

CREATE TABLE bots (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  space_id     UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
  owner_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  title        TEXT NOT NULL DEFAULT '',
  instructions TEXT NOT NULL DEFAULT '',
  model        TEXT NOT NULL DEFAULT '',
  look         JSONB NOT NULL DEFAULT '{}',
  pinned       BOOLEAN NOT NULL DEFAULT false,
  archived_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX bots_space ON bots(space_id) WHERE archived_at IS NULL;

-- The visible transcript. The model's own context lives in eipagent's
-- thread checkpoint; this table is what people see.
CREATE TABLE messages (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  bot_id     UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
  role       TEXT NOT NULL,           -- user | bot | event
  author_id  UUID REFERENCES users(id) ON DELETE SET NULL,
  content    TEXT NOT NULL DEFAULT '',
  data       JSONB NOT NULL DEFAULT '{}',
  run_id     UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX messages_bot_time ON messages(bot_id, created_at DESC, id DESC);

CREATE TABLE runs (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  bot_id      UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
  trigger     TEXT NOT NULL DEFAULT 'chat',
  status      TEXT NOT NULL DEFAULT 'running', -- running | done | stopped | error
  model       TEXT NOT NULL,
  error       TEXT NOT NULL DEFAULT '',
  tokens      INT NOT NULL DEFAULT 0,
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ
);
CREATE INDEX runs_bot ON runs(bot_id, started_at DESC);
