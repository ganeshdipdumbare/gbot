-- Approvals: one mode per account plus exact-match allow/block rules.
ALTER TABLE users ADD COLUMN approval_mode TEXT NOT NULL DEFAULT 'auto'; -- ask | auto | never
CREATE TABLE tool_rules (
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  tool       TEXT NOT NULL,
  rule       TEXT NOT NULL CHECK (rule IN ('allow','block')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, tool)
);

-- Bot settings that grew with the computer and voice.
ALTER TABLE bots ADD COLUMN computer_network BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE bots ADD COLUMN voice TEXT NOT NULL DEFAULT '';
ALTER TABLE bots ADD COLUMN read_aloud BOOLEAN NOT NULL DEFAULT false;

-- Group chats: up to six bots share one conversation.
CREATE TABLE groups (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  space_id    UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  archived_at TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE group_members (
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  bot_id   UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
  position INT NOT NULL,
  PRIMARY KEY (group_id, bot_id)
);

-- A message belongs to a bot's own chat or to a group. In a group,
-- bot_id is the bot that wrote it (null for the person).
ALTER TABLE messages ALTER COLUMN bot_id DROP NOT NULL;
ALTER TABLE messages ADD COLUMN group_id UUID REFERENCES groups(id) ON DELETE CASCADE;
ALTER TABLE messages ADD CONSTRAINT messages_target CHECK (bot_id IS NOT NULL OR group_id IS NOT NULL);
CREATE INDEX messages_group_time ON messages(group_id, created_at DESC, id DESC) WHERE group_id IS NOT NULL;
DROP INDEX messages_bot_time;
CREATE INDEX messages_bot_time ON messages(bot_id, created_at DESC, id DESC) WHERE group_id IS NULL;

-- Routines: prompts a bot runs on a schedule or when a webhook fires.
CREATE TABLE routines (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  bot_id      UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  prompt      TEXT NOT NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('schedule','webhook')),
  schedule    TEXT NOT NULL DEFAULT '',
  secret_hash BYTEA,
  active      BOOLEAN NOT NULL DEFAULT true,
  next_run_at TIMESTAMPTZ,
  last_run_at TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX routines_due ON routines(next_run_at) WHERE active AND kind = 'schedule';

-- Integrations belong to the account that added them.
CREATE TABLE mcp_servers (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  url        TEXT NOT NULL,
  headers    BYTEA,                   -- AES-GCM sealed JSON
  enabled    BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE skills (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  body        TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, name)
);
