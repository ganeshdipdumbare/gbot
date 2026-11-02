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
	OwnerID      string    `json:"ownerId"`
	Name         string    `json:"name"`
	Title        string    `json:"title"`
	Instructions string    `json:"instructions"`
	Model        string    `json:"model"`
	Look         Look      `json:"look"`
	Pinned       bool      `json:"pinned"`
	Network      bool      `json:"computerNetwork"`
	Voice        string    `json:"voice"`
	ReadAloud    bool      `json:"readAloud"`
	Visibility   string    `json:"visibility"`     // private | team | invited
	Role         string    `json:"role,omitempty"` // the viewer's role: owner | manager | collaborator
	CreatedAt    time.Time `json:"createdAt"`
	LastMessage  *Message  `json:"lastMessage,omitempty"`
}

type NewBot struct {
	Name, Title, Instructions, Model string
	Look                             Look
}

// botCols lists the bot columns in scanBot's order, with the bots table
// aliased as b.
const botCols = `b.id, b.space_id, b.owner_id, b.name, b.title, b.instructions, b.model, b.look, b.pinned, b.computer_network, b.voice, b.read_aloud, b.visibility, b.created_at`

// scanBot reads a bot row. extra columns (such as the viewer's membership
// role) are scanned after the bot's own.
func scanBot(row pgx.Row, extra ...any) (*Bot, error) {
	b := &Bot{}
	var look []byte
	dest := append([]any{&b.ID, &b.SpaceID, &b.OwnerID, &b.Name, &b.Title, &b.Instructions, &b.Model, &look,
		&b.Pinned, &b.Network, &b.Voice, &b.ReadAloud, &b.Visibility, &b.CreatedAt}, extra...)
	if err := row.Scan(dest...); err != nil {
		return nil, notFound(err)
	}
	_ = json.Unmarshal(look, &b.Look)
	return b, nil
}

// withRole sets the viewer's role on a bot they can see. Owners manage
// their bot; managers were added as such; anyone else sees a team bot as
// a collaborator.
func (b *Bot) withRole(viewer string, member *string) *Bot {
	switch {
	case b.OwnerID == viewer:
		b.Role = "owner"
	case member != nil:
		b.Role = *member
	case b.Visibility == "team":
		b.Role = "collaborator"
	}
	return b
}

// visibleTo is the access rule. The owner always has it. A private bot has
// no one else. An invited bot is open to the people added to it. A team bot
// is open to every member of its space.
const visibleTo = `(b.owner_id = $2 OR (bm.user_id IS NOT NULL AND b.visibility <> 'private') OR
	(b.visibility = 'team' AND EXISTS (SELECT 1 FROM space_members sm WHERE sm.space_id = b.space_id AND sm.user_id = $2)))`

// ListBots returns the bots the viewer can use in the space, pinned first,
// then by the viewer's most recent activity. Each carries the viewer's
// last message in its own chat.
func (s *Store) ListBots(ctx context.Context, spaceID, viewerID string) ([]*Bot, error) {
	rows, err := s.DB.Query(ctx, `SELECT `+botCols+`, bm.role, m.id, m.role, m.content, m.created_at
		FROM bots b
		LEFT JOIN bot_members bm ON bm.bot_id = b.id AND bm.user_id = $2
		LEFT JOIN LATERAL (SELECT id, role, content, created_at FROM messages
			WHERE bot_id = b.id AND group_id IS NULL AND chat_user_id = $2 AND role IN ('user','bot')
			ORDER BY created_at DESC, id DESC LIMIT 1) m ON true
		WHERE b.space_id = $1 AND b.archived_at IS NULL AND `+visibleTo+`
		ORDER BY b.pinned DESC, COALESCE(m.created_at, b.created_at) DESC`, spaceID, viewerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Bot
	for rows.Next() {
		var member, mid, mrole, mcontent *string
		var mtime *time.Time
		b, err := scanBot(rows, &member, &mid, &mrole, &mcontent, &mtime)
		if err != nil {
			return nil, err
		}
		b.withRole(viewerID, member)
		if mid != nil {
			b.LastMessage = &Message{ID: *mid, BotID: b.ID, Role: *mrole, Content: *mcontent, CreatedAt: *mtime}
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

// GetBotFor loads a bot the viewer can use, in the viewer's space.
// Anything else is ErrNotFound, so a private bot's existence isn't revealed.
func (s *Store) GetBotFor(ctx context.Context, spaceID, viewerID, id string) (*Bot, error) {
	var member *string
	b, err := scanBot(s.DB.QueryRow(ctx, `SELECT `+botCols+`, bm.role
		FROM bots b LEFT JOIN bot_members bm ON bm.bot_id = b.id AND bm.user_id = $2
		WHERE b.id = $1 AND b.space_id = $3 AND b.archived_at IS NULL AND `+visibleTo,
		id, viewerID, spaceID), &member)
	if err != nil {
		return nil, err
	}
	return b.withRole(viewerID, member), nil
}

// GetBot loads a bot in the space without an access check. Only server-side
// code uses it (run setup, handoffs); API handlers use GetBotFor.
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
	if err != nil {
		return nil, err
	}
	return b.withRole(ownerID, nil), nil
}

const botColsBare = `id, space_id, owner_id, name, title, instructions, model, look, pinned, computer_network, voice, read_aloud, visibility, created_at`

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

// ---- Sharing ----

// Member is a person with a role on a bot or in a space.
type Member struct {
	UserID string `json:"userId"`
	Email  string `json:"email"`
	Name   string `json:"name"`
	Role   string `json:"role"`
}

// SetVisibility changes who can see a bot: private, team, or invited.
func (s *Store) SetVisibility(ctx context.Context, botID, visibility string) error {
	_, err := s.DB.Exec(ctx, `UPDATE bots SET visibility=$2, updated_at=now() WHERE id=$1 AND archived_at IS NULL`, botID, visibility)
	return err
}

// BotMembers lists the people a bot is shared with, with their roles.
func (s *Store) BotMembers(ctx context.Context, botID string) ([]Member, error) {
	rows, err := s.DB.Query(ctx, `SELECT u.id, u.email, u.name, bm.role FROM bot_members bm
		JOIN users u ON u.id = bm.user_id WHERE bm.bot_id=$1 ORDER BY u.name`, botID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Member, error) {
		var m Member
		return m, r.Scan(&m.UserID, &m.Email, &m.Name, &m.Role)
	})
}

// SetBotMember adds a person to a bot with a role, or changes their role.
// An empty role removes them.
func (s *Store) SetBotMember(ctx context.Context, botID, userID, role string) error {
	if role == "" {
		_, err := s.DB.Exec(ctx, `DELETE FROM bot_members WHERE bot_id=$1 AND user_id=$2`, botID, userID)
		return err
	}
	_, err := s.DB.Exec(ctx, `INSERT INTO bot_members(bot_id,user_id,role) VALUES($1,$2,$3)
		ON CONFLICT (bot_id,user_id) DO UPDATE SET role=EXCLUDED.role`, botID, userID, role)
	return err
}

// BotAudience lists the users who may see a bot's live state, by the same
// rule as access: the owner; the people added to an invited bot; every
// member of the space for a team bot. It reads the bot's current sharing,
// so a change takes effect at once.
func (s *Store) BotAudience(ctx context.Context, botID string) ([]string, error) {
	rows, err := s.DB.Query(ctx, `SELECT owner_id::text FROM bots WHERE id=$1
		UNION SELECT bm.user_id::text FROM bot_members bm JOIN bots b ON b.id=bm.bot_id
			WHERE bm.bot_id=$1 AND b.visibility='invited'
		UNION SELECT sm.user_id::text FROM bots b JOIN space_members sm ON sm.space_id=b.space_id
			WHERE b.id=$1 AND b.visibility='team'`, botID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[string])
}
