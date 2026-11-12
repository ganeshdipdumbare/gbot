-- Codex providers use the ChatGPT login Codex saved on the server.
ALTER TABLE model_providers DROP CONSTRAINT model_providers_kind_check;
ALTER TABLE model_providers ADD CONSTRAINT model_providers_kind_check
  CHECK (kind IN ('openai','anthropic','gemini','codex'));
