-- Per-bot browser allowlist. Empty means all sites, as before; a list
-- limits browser navigation (and anything a click or redirect opens) to
-- those hosts and their subdomains.
ALTER TABLE bots ADD COLUMN allowed_hosts JSONB NOT NULL DEFAULT '[]'::jsonb;
