-- Sharing. A space is a team: its members can use a bot only once the
-- owner shares it. A bot is private to its owner until then.
ALTER TABLE bots ADD COLUMN visibility TEXT NOT NULL DEFAULT 'private'
  CHECK (visibility IN ('private','team','invited'));

-- People a bot is shared with. Managers can change how the bot works and
-- who uses it; collaborators can only chat with it.
CREATE TABLE bot_members (
  bot_id  UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role    TEXT NOT NULL CHECK (role IN ('manager','collaborator')),
  PRIMARY KEY (bot_id, user_id)
);

-- Each person's chat with a bot is their own. chat_user_id names whose
-- chat a bot message belongs to; group messages leave it empty because
-- the group itself belongs to one person.
ALTER TABLE messages ADD COLUMN chat_user_id UUID REFERENCES users(id) ON DELETE CASCADE;
UPDATE messages m SET chat_user_id = b.owner_id FROM bots b WHERE m.bot_id = b.id AND m.group_id IS NULL;
CREATE INDEX messages_chat ON messages(bot_id, chat_user_id, created_at DESC, id DESC) WHERE group_id IS NULL;

-- Usage is attributed to the person a run was for.
ALTER TABLE runs ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE SET NULL;
UPDATE runs r SET user_id = b.owner_id FROM bots b WHERE r.bot_id = b.id;

-- Group chats belong to whoever made them.
ALTER TABLE groups ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE CASCADE;
UPDATE groups g SET user_id = (SELECT m.user_id FROM space_members m
  WHERE m.space_id = g.space_id AND m.role = 'owner' LIMIT 1);

-- Sessions remember which team (space) the person picked.
ALTER TABLE sessions ADD COLUMN space_id UUID REFERENCES spaces(id) ON DELETE SET NULL;

-- Routines belong to whoever created them: their runs use that person's
-- approvals, tools and memory, and report into their own chat.
ALTER TABLE routines ADD COLUMN user_id UUID REFERENCES users(id) ON DELETE CASCADE;
UPDATE routines r SET user_id = b.owner_id FROM bots b WHERE r.bot_id = b.id;
