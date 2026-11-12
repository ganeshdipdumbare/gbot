-- OAuth tokens for integrations that sign in with a click. Sealed with
-- the same key as other integration secrets; the access token is never
-- returned to the browser.
ALTER TABLE mcp_servers ADD COLUMN oauth BYTEA;
