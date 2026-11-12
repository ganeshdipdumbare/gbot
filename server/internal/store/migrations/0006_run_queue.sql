-- Durable queue for chat runs: a restart resumes what people sent.
-- The runtime also keeps its in-memory lane for ordering; this table is
-- the write-ahead record that survives a crash.
CREATE TABLE run_queue (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  space_id     UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
  bot_id       UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
  group_id     UUID REFERENCES groups(id) ON DELETE CASCADE,
  chat_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  prompt       TEXT NOT NULL DEFAULT '',
  trigger      TEXT NOT NULL DEFAULT 'chat',
  untrusted    BOOLEAN NOT NULL DEFAULT false,
  depth        INT NOT NULL DEFAULT 0,
  thread       TEXT NOT NULL DEFAULT '',
  decisions    JSONB,
  status       TEXT NOT NULL DEFAULT 'queued', -- queued | paused | running | waiting | done | stopped | error | cancelled
  error        TEXT NOT NULL DEFAULT '',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at   TIMESTAMPTZ,
  finished_at  TIMESTAMPTZ
);
CREATE INDEX run_queue_active ON run_queue(created_at) WHERE status IN ('queued','paused','running');
CREATE INDEX run_queue_bot ON run_queue(bot_id, chat_user_id, created_at DESC);
CREATE INDEX run_queue_group ON run_queue(group_id, created_at DESC) WHERE group_id IS NOT NULL;
