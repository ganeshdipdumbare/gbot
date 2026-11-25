package store

import (
	"context"

	"github.com/jackc/pgx/v5"
)

// Summary is the rolling summary of the oldest turns of one conversation.
// Covered counts the messages it already includes.
type Summary struct {
	ThreadID string
	Covered  int
	Summary  string
}

// GetSummary returns a thread's summary, if it has one.
func (s *Store) GetSummary(ctx context.Context, threadID string) (*Summary, error) {
	sum := &Summary{ThreadID: threadID}
	err := s.DB.QueryRow(ctx, `SELECT covered, summary FROM summaries WHERE thread_id=$1`, threadID).
		Scan(&sum.Covered, &sum.Summary)
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return sum, nil
}

// SaveSummary stores an updated rolling summary. Summaries are written in
// the background, so one that finishes late is dropped: after the chat was
// cleared (its thread is gone, or a new one is shorter than the summary
// covers), or once a summary covering more was saved.
func (s *Store) SaveSummary(ctx context.Context, threadID string, covered int, text string) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO summaries(thread_id,covered,summary)
		SELECT $1,$2,$3 WHERE EXISTS (
			SELECT 1 FROM agent_sessions WHERE id=$1 AND (jsonb_typeof(state->'messages') IS DISTINCT FROM 'array'
				OR jsonb_array_length(state->'messages') >= $2))
		ON CONFLICT (thread_id) DO UPDATE SET covered=EXCLUDED.covered, summary=EXCLUDED.summary, updated_at=now()
		WHERE summaries.covered <= EXCLUDED.covered`,
		threadID, covered, text)
	return err
}
