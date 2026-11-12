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

// SaveSummary stores an updated rolling summary.
func (s *Store) SaveSummary(ctx context.Context, threadID string, covered int, text string) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO summaries(thread_id,covered,summary) VALUES($1,$2,$3)
		ON CONFLICT (thread_id) DO UPDATE SET covered=EXCLUDED.covered, summary=EXCLUDED.summary, updated_at=now()`,
		threadID, covered, text)
	return err
}
