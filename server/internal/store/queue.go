package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
)

// QueueItem is one chat message waiting for its bot. It is the durable
// record behind the runtime's in-memory lane: the lane keeps order, this
// row survives a restart. Only chat, routine and webhook runs are
// recorded; resumes and bot-to-bot exchanges are derived work.
type QueueItem struct {
	ID         string         `json:"id"`
	SpaceID    string         `json:"-"`
	BotID      string         `json:"-"`
	GroupID    string         `json:"-"`
	ChatUser   string         `json:"-"`
	Prompt     string         `json:"prompt"`
	Trigger    string         `json:"-"`
	Untrusted  bool           `json:"-"`
	Depth      int            `json:"-"`
	Thread     string         `json:"-"`
	Decisions  map[string]any `json:"-"`
	Status     string         `json:"status"` // queued | steered | running | waiting | done | stopped | error | cancelled
	Error      string         `json:"error,omitempty"`
	CreatedAt  time.Time      `json:"createdAt"`
	StartedAt  *time.Time     `json:"startedAt,omitempty"`
	FinishedAt *time.Time     `json:"finishedAt,omitempty"`
}

const queueCols = `id, space_id, bot_id, group_id, chat_user_id, prompt, trigger, untrusted, depth, thread, decisions, status, error, created_at, started_at, finished_at`

func scanQueue(row pgx.Row) (*QueueItem, error) {
	q := &QueueItem{}
	var group, chatUser, thread *string
	var decisions []byte
	if err := row.Scan(&q.ID, &q.SpaceID, &q.BotID, &group, &chatUser, &q.Prompt, &q.Trigger, &q.Untrusted,
		&q.Depth, &thread, &decisions, &q.Status, &q.Error, &q.CreatedAt, &q.StartedAt, &q.FinishedAt); err != nil {
		return nil, notFound(err)
	}
	if group != nil {
		q.GroupID = *group
	}
	if chatUser != nil {
		q.ChatUser = *chatUser
	}
	if thread != nil {
		q.Thread = *thread
	}
	if len(decisions) > 0 {
		_ = json.Unmarshal(decisions, &q.Decisions)
	}
	return q, nil
}

// Enqueue records a run in the queue and fills in its id and time.
func (s *Store) Enqueue(ctx context.Context, q *QueueItem) error {
	var decisions []byte
	if q.Decisions != nil {
		decisions, _ = json.Marshal(q.Decisions)
	}
	return s.DB.QueryRow(ctx, `INSERT INTO run_queue(space_id,bot_id,group_id,chat_user_id,prompt,trigger,untrusted,depth,thread,decisions)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING id, created_at`,
		q.SpaceID, q.BotID, nullable(q.GroupID), nullable(q.ChatUser), q.Prompt, q.Trigger, q.Untrusted, q.Depth, q.Thread, decisions).
		Scan(&q.ID, &q.CreatedAt)
}

// SetQueueStatus moves one item and stamps the matching time.
func (s *Store) SetQueueStatus(ctx context.Context, id, status, errText string) error {
	_, err := s.DB.Exec(ctx, `UPDATE run_queue SET status=$2, error=$3,
		started_at=CASE WHEN $2='running' THEN now() ELSE started_at END,
		finished_at=CASE WHEN $2 IN ('waiting','done','stopped','error','cancelled') THEN now() ELSE finished_at END
		WHERE id=$1`, id, status, errText)
	return err
}

// QueueItem loads one item; the caller authorizes it.
func (s *Store) QueueItem(ctx context.Context, id string) (*QueueItem, error) {
	return scanQueue(s.DB.QueryRow(ctx, `SELECT `+queueCols+` FROM run_queue WHERE id=$1`, id))
}

// ActiveQueue lists what a chat still has to run: the current turn and
// everything queued or steered behind it.
func (s *Store) ActiveQueue(ctx context.Context, botID, chatUser, groupID string) ([]*QueueItem, error) {
	q := `SELECT ` + queueCols + ` FROM run_queue WHERE status IN ('queued','steered','running') AND `
	args := []any{}
	if groupID != "" {
		q += `group_id=$1 ORDER BY created_at`
		args = append(args, groupID)
	} else {
		q += `bot_id=$1 AND chat_user_id=$2 AND group_id IS NULL ORDER BY created_at`
		args = append(args, botID, chatUser)
	}
	rows, err := s.DB.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (*QueueItem, error) { return scanQueue(r) })
}

// RecoverQueue returns the items a previous process left queued. A
// message steered into a turn that never read it waits in line again.
func (s *Store) RecoverQueue(ctx context.Context) ([]*QueueItem, error) {
	rows, err := s.DB.Query(ctx, `SELECT `+queueCols+` FROM run_queue WHERE status IN ('queued','steered') ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (*QueueItem, error) { return scanQueue(r) })
}

// FailRunningQueue marks the turns a crash interrupted. Their prompt stays
// in the chat, so the person can send it again.
func (s *Store) FailRunningQueue(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `UPDATE run_queue SET status='error', error='server restarted', finished_at=now() WHERE status='running'`)
	return err
}

// CancelQueue cancels what one chat still has to run, leaving the turn
// that is running now alone.
func (s *Store) CancelQueue(ctx context.Context, botID, userID, groupID string) error {
	if groupID != "" {
		_, err := s.DB.Exec(ctx, `UPDATE run_queue SET status='cancelled', finished_at=now()
			WHERE group_id=$1 AND status IN ('queued','steered')`, groupID)
		return err
	}
	_, err := s.DB.Exec(ctx, `UPDATE run_queue SET status='cancelled', finished_at=now()
		WHERE bot_id=$1 AND chat_user_id=$2 AND group_id IS NULL AND status IN ('queued','steered')`, botID, userID)
	return err
}

// CancelQueueBot drops every person's waiting work with an archived bot.
func (s *Store) CancelQueueBot(ctx context.Context, botID string) error {
	_, err := s.DB.Exec(ctx, `UPDATE run_queue SET status='cancelled', finished_at=now()
		WHERE bot_id=$1 AND status IN ('queued','steered','running')`, botID)
	return err
}

// PruneQueue drops finished records after a week; the chat is the record
// people read.
func (s *Store) PruneQueue(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM run_queue WHERE finished_at < now() - interval '7 days'`)
	return err
}
