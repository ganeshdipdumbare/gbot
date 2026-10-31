package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
)

// MaxBotsPerSpace keeps the roster scannable (Grok Bot uses ~50).
const MaxBotsPerSpace = 50

type Look map[string]any

type Bot struct {
	ID           string    `json:"id"`
	SpaceID      string    `json:"spaceId"`
	Name         string    `json:"name"`
	Title        string    `json:"title"`
	Instructions string    `json:"instructions"`
	Model        string    `json:"model"`
	Look         Look      `json:"look"`
	Pinned       bool      `json:"pinned"`
	Network      bool      `json:"computerNetwork"`
	Voice        string    `json:"voice"`
	ReadAloud    bool      `json:"readAloud"`
	CreatedAt    time.Time `json:"createdAt"`
	LastMessage  *Message  `json:"lastMessage,omitempty"`
}

type NewBot struct {
	Name, Title, Instructions, Model string
	Look                             Look
}

const botCols = `b.id, b.space_id, b.name, b.title, b.instructions, b.model, b.look, b.pinned, b.computer_network, b.voice, b.read_aloud, b.created_at`

func scanBot(row pgx.Row) (*Bot, error) {
	b := &Bot{}
	var look []byte
	if err := row.Scan(&b.ID, &b.SpaceID, &b.Name, &b.Title, &b.Instructions, &b.Model, &look, &b.Pinned, &b.Network, &b.Voice, &b.ReadAloud, &b.CreatedAt); err != nil {
		return nil, notFound(err)
	}
	_ = json.Unmarshal(look, &b.Look)
	return b, nil
}

// ListBots returns the space's roster with each bot's latest message,
// pinned first, then by most recent activity.
func (s *Store) ListBots(ctx context.Context, spaceID string) ([]*Bot, error) {
	rows, err := s.DB.Query(ctx, `SELECT `+botCols+`, m.id, m.role, m.content, m.created_at
		FROM bots b
		LEFT JOIN LATERAL (SELECT id, role, content, created_at FROM messages
			WHERE bot_id=b.id AND group_id IS NULL AND role IN ('user','bot') ORDER BY created_at DESC, id DESC LIMIT 1) m ON true
		WHERE b.space_id=$1 AND b.archived_at IS NULL
		ORDER BY b.pinned DESC, COALESCE(m.created_at, b.created_at) DESC`, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Bot
	for rows.Next() {
		b := &Bot{}
		var look []byte
		var mid, mrole, mcontent *string
		var mtime *time.Time
		if err := rows.Scan(&b.ID, &b.SpaceID, &b.Name, &b.Title, &b.Instructions, &b.Model, &look, &b.Pinned, &b.Network, &b.Voice, &b.ReadAloud, &b.CreatedAt,
			&mid, &mrole, &mcontent, &mtime); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(look, &b.Look)
		if mid != nil {
			b.LastMessage = &Message{ID: *mid, BotID: b.ID, Role: *mrole, Content: *mcontent, CreatedAt: *mtime}
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

func (s *Store) GetBot(ctx context.Context, spaceID, id string) (*Bot, error) {
	return scanBot(s.DB.QueryRow(ctx, `SELECT `+botCols+` FROM bots b
		WHERE b.id=$1 AND b.space_id=$2 AND b.archived_at IS NULL`, id, spaceID))
}

var ErrBotLimit = &limitError{"you can have up to 50 bots"}

type limitError struct{ msg string }

func (e *limitError) Error() string { return e.msg }

func (s *Store) CreateBot(ctx context.Context, spaceID, ownerID string, nb NewBot) (*Bot, error) {
	look, _ := json.Marshal(nb.Look)
	b, err := scanBot(s.DB.QueryRow(ctx, `INSERT INTO bots(space_id,owner_id,name,title,instructions,model,look)
		SELECT $1,$2,$3,$4,$5,$6,$7
		WHERE (SELECT count(*) FROM bots WHERE space_id=$1 AND archived_at IS NULL) < $8
		RETURNING `+botColsBare, spaceID, ownerID, nb.Name, nb.Title, nb.Instructions, nb.Model, look, MaxBotsPerSpace))
	if err == ErrNotFound {
		return nil, ErrBotLimit
	}
	return b, err
}

const botColsBare = `id, space_id, name, title, instructions, model, look, pinned, computer_network, voice, read_aloud, created_at`

type BotPatch struct {
	Name         *string `json:"name"`
	Title        *string `json:"title"`
	Instructions *string `json:"instructions"`
	Model        *string `json:"model"`
	Look         *Look   `json:"look"`
	Pinned       *bool   `json:"pinned"`
	Network      *bool   `json:"computerNetwork"`
	Voice        *string `json:"voice"`
	ReadAloud    *bool   `json:"readAloud"`
}

func (s *Store) UpdateBot(ctx context.Context, spaceID, id string, p BotPatch) (*Bot, error) {
	var look []byte
	if p.Look != nil {
		look, _ = json.Marshal(*p.Look)
	}
	return scanBot(s.DB.QueryRow(ctx, `UPDATE bots SET
		name=COALESCE($3,name), title=COALESCE($4,title), instructions=COALESCE($5,instructions),
		model=COALESCE($6,model), look=COALESCE($7::jsonb,look), pinned=COALESCE($8,pinned),
		computer_network=COALESCE($9,computer_network), voice=COALESCE($10,voice), read_aloud=COALESCE($11,read_aloud), updated_at=now()
		WHERE id=$1 AND space_id=$2 AND archived_at IS NULL RETURNING `+botColsBare,
		id, spaceID, p.Name, p.Title, p.Instructions, p.Model, look, p.Pinned, p.Network, p.Voice, p.ReadAloud))
}

func (s *Store) ArchiveBot(ctx context.Context, spaceID, id string) error {
	t, err := s.DB.Exec(ctx, `UPDATE bots SET archived_at=now() WHERE id=$1 AND space_id=$2 AND archived_at IS NULL`, id, spaceID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}

// GetBotAnySpace loads a bot by id alone; only for server-side jobs
// (routines, webhooks) that already proved who they act for.
func (s *Store) GetBotAnySpace(ctx context.Context, id string) (*Bot, error) {
	return scanBot(s.DB.QueryRow(ctx, `SELECT `+botCols+` FROM bots b WHERE b.id=$1 AND b.archived_at IS NULL`, id))
}
