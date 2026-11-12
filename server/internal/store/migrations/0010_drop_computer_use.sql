-- Computer use (the optional NorthCUA integration) is gone. Browsing and
-- CLI work stay on the bot's computer; the browser allowlist replaces the
-- need for a vision model driving the screen.
ALTER TABLE bots DROP COLUMN IF EXISTS computer_use;
