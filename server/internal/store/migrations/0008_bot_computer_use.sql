-- Opt-in GUI control for the bot's computer. Off by default: it lets a
-- vision model click and type anywhere on the bot's own screen.
ALTER TABLE bots ADD COLUMN computer_use BOOLEAN NOT NULL DEFAULT false;
