-- A Slack workspace a space has connected, and which bot answers where.
--
-- Both secrets are sealed with the server key: the bot token posts replies,
-- the signing secret proves an inbound request really came from Slack.
-- Neither is ever returned by the API.
CREATE TABLE slack_installs (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  space_id    UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
  owner_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  team_id     TEXT NOT NULL,
  team_name   TEXT NOT NULL DEFAULT '',
  bot_token   BYTEA NOT NULL,
  signing_key BYTEA NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (team_id)
);

-- One channel talks to exactly one bot, so an inbound message never has to
-- guess who it is for.
CREATE TABLE slack_links (
  install_id UUID NOT NULL REFERENCES slack_installs(id) ON DELETE CASCADE,
  channel_id TEXT NOT NULL,
  channel    TEXT NOT NULL DEFAULT '',
  bot_id     UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (install_id, channel_id)
);
CREATE INDEX slack_links_bot ON slack_links(bot_id);
