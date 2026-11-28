-- Pause is gone from the queue: a person steers a waiting message into the
-- running turn instead. Anything left paused goes back in line.
UPDATE run_queue SET status='queued' WHERE status='paused';

DROP INDEX IF EXISTS run_queue_active;
CREATE INDEX run_queue_active ON run_queue(created_at) WHERE status IN ('queued','steered','running');
