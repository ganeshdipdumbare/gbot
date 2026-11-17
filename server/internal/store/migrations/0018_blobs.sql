-- Bytes a bot's tool handed back and the chat shows: a diagram from an
-- MCP integration, a screenshot, a picture. The chat message points at a
-- row here; deleting the bot takes its pictures with it.
CREATE TABLE blobs (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id     UUID REFERENCES bots(id) ON DELETE CASCADE,
    user_id    UUID REFERENCES users(id) ON DELETE CASCADE,
    mime       TEXT NOT NULL,
    bytes      BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
