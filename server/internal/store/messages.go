package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
)

// Message is one entry in a bot's chat or a group chat.
type Message struct {
	ID        string         `json:"id"`
	BotID     string         `json:"botId,omitempty"`   // the bot's chat, or the author in a group
	GroupID   string         `json:"groupId,omitempty"` // set for group chats
	Role      string         `json:"role"`              // user | bot | event | card
	Content   string         `json:"content"`
	Data      map[string]any `json:"data,omitempty"`
	RunID     *string        `json:"runId,omitempty"`
	CreatedAt time.Time      `json:"createdAt"`
}

func nullable(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func (s *Store) AddMessage(ctx context.Context, m *Message, authorID *string) error {
	data := []byte("{}")
	if m.Data != nil {
		data, _ = json.Marshal(m.Data)
	}
	return s.DB.QueryRow(ctx, `INSERT INTO messages(bot_id,group_id,role,author_id,content,data,run_id) VALUES($1,$2,$3,$4,$5,$6,$7)
		RETURNING id, created_at`, nullable(m.BotID), nullable(m.GroupID), m.Role, authorID, m.Content, data, m.RunID).Scan(&m.ID, &m.CreatedAt)
}

func (s *Store) SetMessageContent(ctx context.Context, id, content string) error {
	_, err := s.DB.Exec(ctx, `UPDATE messages SET content=$2 WHERE id=$1`, id, content)
	return err
}

// MergeMessageData merges fields into a message's data (cards use this
// to record their outcome).
func (s *Store) MergeMessageData(ctx context.Context, id string, data map[string]any) (*Message, error) {
	raw, _ := json.Marshal(data)
	return scanMessage(s.DB.QueryRow(ctx, `UPDATE messages SET data = data || $2::jsonb WHERE id=$1
		RETURNING `+msgCols, id, raw))
}

const msgCols = `id, bot_id, group_id, role, content, data, run_id, created_at`

func scanMessage(row pgx.Row) (*Message, error) {
	m := &Message{}
	var bot, group *string
	var data []byte
	if err := row.Scan(&m.ID, &bot, &group, &m.Role, &m.Content, &data, &m.RunID, &m.CreatedAt); err != nil {
		return nil, notFound(err)
	}
	if bot != nil {
		m.BotID = *bot
	}
	if group != nil {
		m.GroupID = *group
	}
	_ = json.Unmarshal(data, &m.Data)
	return m, nil
}

func (s *Store) GetMessage(ctx context.Context, id string) (*Message, error) {
	return scanMessage(s.DB.QueryRow(ctx, `SELECT `+msgCols+` FROM messages WHERE id=$1`, id))
}

// PendingCards returns a chat's unanswered cards, oldest first.
func (s *Store) PendingCards(ctx context.Context, botID, groupID string) ([]*Message, error) {
	q := `SELECT ` + msgCols + ` FROM messages WHERE role='card' AND data->>'status'='pending' AND `
	arg := botID
	if groupID != "" {
		q += `group_id=$1`
		arg = groupID
	} else {
		q += `bot_id=$1 AND group_id IS NULL`
	}
	return s.listMessages(ctx, q+` ORDER BY created_at`, arg)
}

func (s *Store) listMessages(ctx context.Context, q string, args ...any) ([]*Message, error) {
	rows, err := s.DB.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Message
	for rows.Next() {
		m, err := scanMessage(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// ListMessages pages backwards from before (keyset), newest last. The
// caller must already have authorized the bot or group.
func (s *Store) ListMessages(ctx context.Context, botID string, before *time.Time, limit int) ([]*Message, error) {
	return s.page(ctx, `bot_id=$1 AND group_id IS NULL`, botID, before, limit)
}

func (s *Store) ListGroupMessages(ctx context.Context, groupID string, before *time.Time, limit int) ([]*Message, error) {
	return s.page(ctx, `group_id=$1`, groupID, before, limit)
}

func (s *Store) page(ctx context.Context, where, id string, before *time.Time, limit int) ([]*Message, error) {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	out, err := s.listMessages(ctx, `SELECT `+msgCols+` FROM messages WHERE `+where+`
		AND ($2::timestamptz IS NULL OR created_at < $2) ORDER BY created_at DESC, id DESC LIMIT $3`, id, before, limit)
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, err
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
