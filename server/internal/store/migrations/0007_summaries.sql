-- A rolling summary of the turns that no longer fit in the model's
-- context. covered is how many dropped messages it already includes, so
-- a turn never summarizes the same prefix twice.
CREATE TABLE summaries (
  thread_id  TEXT PRIMARY KEY,
  covered    INT NOT NULL DEFAULT 0,
  summary    TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
