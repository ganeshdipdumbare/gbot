package store

import (
	"context"
	"encoding/json"
	"time"
)

type Message struct {
	ID        string         `json:"id"`
	BotID     string         `json:"botId"`
	Role      string         `json:"role"` // user | bot | event
	Content   string         `json:"content"`
	Data      map[string]any `json:"data,omitempty"`
	RunID     *string        `json:"runId,omitempty"`
	CreatedAt time.Time      `json:"createdAt"`
}

func (s *Store) AddMessage(ctx context.Context, m *Message, authorID *string) error {
	data, _ := json.Marshal(m.Data)
	if m.Data == nil {
		data = []byte("{}")
	}
	return s.DB.QueryRow(ctx, `INSERT INTO messages(bot_id,role,author_id,content,data,run_id) VALUES($1,$2,$3,$4,$5,$6)
		RETURNING id, created_at`, m.BotID, m.Role, authorID, m.Content, data, m.RunID).Scan(&m.ID, &m.CreatedAt)
}

func (s *Store) SetMessageContent(ctx context.Context, id, content string) error {
	_, err := s.DB.Exec(ctx, `UPDATE messages SET content=$2 WHERE id=$1`, id, content)
	return err
}

// ListMessages pages backwards from before (keyset), newest last.
// The bot must already be authorized for the caller's space.
func (s *Store) ListMessages(ctx context.Context, botID string, before *time.Time, limit int) ([]*Message, error) {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	rows, err := s.DB.Query(ctx, `SELECT id, bot_id, role, content, data, run_id, created_at FROM messages
		WHERE bot_id=$1 AND ($2::timestamptz IS NULL OR created_at < $2)
		ORDER BY created_at DESC, id DESC LIMIT $3`, botID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Message
	for rows.Next() {
		m := &Message{}
		var data []byte
		if err := rows.Scan(&m.ID, &m.BotID, &m.Role, &m.Content, &data, &m.RunID, &m.CreatedAt); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(data, &m.Data)
		out = append(out, m)
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, rows.Err()
}

func (s *Store) StartRun(ctx context.Context, botID, trigger, model string) (id string, err error) {
	err = s.DB.QueryRow(ctx, `INSERT INTO runs(bot_id,trigger,model) VALUES($1,$2,$3) RETURNING id`, botID, trigger, model).Scan(&id)
	return
}

func (s *Store) FinishRun(ctx context.Context, id, status, errText string, tokens int) error {
	_, err := s.DB.Exec(ctx, `UPDATE runs SET status=$2, error=$3, tokens=$4, finished_at=now() WHERE id=$1`, id, status, errText, tokens)
	return err
}

// FailInterruptedRuns marks runs left running by a crashed process.
func (s *Store) FailInterruptedRuns(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `UPDATE runs SET status='error', error='server restarted', finished_at=now() WHERE status='running'`)
	return err
}
