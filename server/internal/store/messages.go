package store

import (
	"context"
	"encoding/json"
	"strconv"
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
	// ChatUser is whose private chat a bot's message belongs to. It's
	// server-side only: clients never see other people's chats in a list.
	ChatUser string `json:"-"`
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
	// A group's messages belong to the group, not to a person's private chat.
	chatUser := nullable(m.ChatUser)
	if m.GroupID != "" {
		chatUser = nil
	}
	return s.DB.QueryRow(ctx, `INSERT INTO messages(bot_id,group_id,chat_user_id,role,author_id,content,data,run_id) VALUES($1,$2,$3,$4,$5,$6,$7,$8)
		RETURNING id, created_at`, nullable(m.BotID), nullable(m.GroupID), chatUser, m.Role, authorID, m.Content, data, m.RunID).Scan(&m.ID, &m.CreatedAt)
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

const msgCols = `id, bot_id, group_id, role, content, data, run_id, created_at, chat_user_id`

func scanMessage(row pgx.Row) (*Message, error) {
	m := &Message{}
	var bot, group, chatUser *string
	var data []byte
	if err := row.Scan(&m.ID, &bot, &group, &m.Role, &m.Content, &data, &m.RunID, &m.CreatedAt, &chatUser); err != nil {
		return nil, notFound(err)
	}
	if bot != nil {
		m.BotID = *bot
	}
	if group != nil {
		m.GroupID = *group
	}
	if chatUser != nil {
		m.ChatUser = *chatUser
	}
	_ = json.Unmarshal(data, &m.Data)
	return m, nil
}

func (s *Store) GetMessage(ctx context.Context, id string) (*Message, error) {
	return scanMessage(s.DB.QueryRow(ctx, `SELECT `+msgCols+` FROM messages WHERE id=$1`, id))
}

// PendingCards returns a chat's unanswered cards, oldest first. A bot's
// chat is one person's (userID); a group is identified by groupID alone.
func (s *Store) PendingCards(ctx context.Context, botID, userID, groupID string) ([]*Message, error) {
	q := `SELECT ` + msgCols + ` FROM messages WHERE role='card' AND data->>'status'='pending' AND `
	if groupID != "" {
		return s.listMessages(ctx, q+`group_id=$1 ORDER BY created_at`, groupID)
	}
	return s.listMessages(ctx, q+`bot_id=$1 AND chat_user_id=$2 AND group_id IS NULL ORDER BY created_at`, botID, userID)
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

// ListMessages pages backwards from before (keyset), newest last, through
// one person's chat with a bot. The caller must already have authorized
// the bot for that person.
func (s *Store) ListMessages(ctx context.Context, botID, userID string, before *time.Time, limit int) ([]*Message, error) {
	return s.page(ctx, `bot_id=$1 AND chat_user_id=$2 AND group_id IS NULL`, []any{botID, userID}, before, limit)
}

// ListGroupMessages pages through a group chat. The caller must already
// have authorized the group.
func (s *Store) ListGroupMessages(ctx context.Context, groupID string, before *time.Time, limit int) ([]*Message, error) {
	return s.page(ctx, `group_id=$1`, []any{groupID}, before, limit)
}

func (s *Store) page(ctx context.Context, where string, args []any, before *time.Time, limit int) ([]*Message, error) {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	n := len(args)
	args = append(args, before, limit)
	out, err := s.listMessages(ctx, `SELECT `+msgCols+` FROM messages WHERE `+where+`
		AND ($`+strconv.Itoa(n+1)+`::timestamptz IS NULL OR created_at < $`+strconv.Itoa(n+1)+`) ORDER BY created_at DESC, id DESC LIMIT $`+strconv.Itoa(n+2), args...)
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, err
}

// SearchMessages finds what was said in one chat, newest first. It backs
// the bot's history_search tool: context trimming hides old turns from the
// model, this lets it look them up. A group is named by groupID; otherwise
// the chat is the bot's own with userID.
func (s *Store) SearchMessages(ctx context.Context, botID, userID, groupID, query string, since time.Time, limit int) ([]*Message, error) {
	if limit <= 0 || limit > 50 {
		limit = 10
	}
	where, args := `bot_id=$1 AND chat_user_id=$2 AND group_id IS NULL`, []any{botID, userID}
	if groupID != "" {
		where, args = `group_id=$1`, []any{groupID}
	}
	args = append(args, since)
	sinceIdx := len(args)
	args = append(args, limit)
	limitIdx := len(args)
	q := `SELECT ` + msgCols + ` FROM messages WHERE ` + where + `
		AND role IN ('user','bot') AND created_at >= $` + strconv.Itoa(sinceIdx)
	if query != "" {
		pattern := "%" + escapeLike(query) + "%"
		args = append(args, pattern)
		q += ` AND content ILIKE $` + strconv.Itoa(len(args)) + ` ESCAPE '\'`
	}
	q += ` ORDER BY created_at DESC, id DESC LIMIT $` + strconv.Itoa(limitIdx)
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

// StartRun records a run and the person it was for.
func (s *Store) StartRun(ctx context.Context, botID, userID, trigger, model string) (id string, err error) {
	err = s.DB.QueryRow(ctx, `INSERT INTO runs(bot_id,user_id,trigger,model) VALUES($1,$2,$3,$4) RETURNING id`,
		botID, nullable(userID), trigger, model).Scan(&id)
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

// Bot-to-bot exchange cards. Both cards of one message share its data;
// see docs/protocol/bot-asks.md.

const botmsgWhere = `role='event' AND data->>'kind'='botmsg'`

// RoundCards returns every exchange card of one round, oldest first.
func (s *Store) RoundCards(ctx context.Context, round string) ([]*Message, error) {
	return s.listMessages(ctx, `SELECT `+msgCols+` FROM messages WHERE `+botmsgWhere+` AND data->>'round'=$1 ORDER BY created_at`, round)
}

// AskCards returns the two cards of one message.
func (s *Store) AskCards(ctx context.Context, ask string) ([]*Message, error) {
	return s.listMessages(ctx, `SELECT `+msgCols+` FROM messages WHERE `+botmsgWhere+` AND data->>'ask'=$1 ORDER BY created_at`, ask)
}

// OpenAsks returns the receivers' cards of messages that were never
// answered, from the last two days: what a restart has to pick up again.
func (s *Store) OpenAsks(ctx context.Context) ([]*Message, error) {
	return s.listMessages(ctx, `SELECT `+msgCols+` FROM messages WHERE `+botmsgWhere+`
		AND data->>'status' IN ('sent','working') AND data ? 'ask' AND bot_id::text = data->>'to'
		AND created_at > now() - interval '2 days' ORDER BY created_at`)
}

// UnjoinedRounds returns the rounds whose answers never went back to the
// bot that asked, from the last two days.
func (s *Store) UnjoinedRounds(ctx context.Context) ([]string, error) {
	rows, err := s.DB.Query(ctx, `SELECT DISTINCT data->>'round' FROM messages WHERE `+botmsgWhere+`
		AND data ? 'round' AND bot_id::text = data->>'from' AND coalesce(data->>'joined','') <> 'true'
		AND created_at > now() - interval '2 days'`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}
