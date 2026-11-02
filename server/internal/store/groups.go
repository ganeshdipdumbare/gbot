package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

// MaxGroupBots keeps a group chat readable (Grok Bot uses six).
const MaxGroupBots = 6

type Group struct {
	ID          string    `json:"id"`
	SpaceID     string    `json:"spaceId"`
	UserID      string    `json:"userId"` // the person who made the group; only they see it
	Name        string    `json:"name"`
	BotIDs      []string  `json:"botIds"`
	CreatedAt   time.Time `json:"createdAt"`
	LastMessage *Message  `json:"lastMessage,omitempty"`
}

const groupCols = `g.id, g.space_id, g.user_id, g.name, g.created_at,
	COALESCE((SELECT array_agg(bot_id::text ORDER BY position) FROM group_members WHERE group_id=g.id), '{}')`

func scanGroup(row pgx.Row) (*Group, error) {
	g := &Group{}
	if err := row.Scan(&g.ID, &g.SpaceID, &g.UserID, &g.Name, &g.CreatedAt, &g.BotIDs); err != nil {
		return nil, notFound(err)
	}
	return g, nil
}

// ListGroups returns the group chats the person made in the space.
func (s *Store) ListGroups(ctx context.Context, spaceID, userID string) ([]*Group, error) {
	rows, err := s.DB.Query(ctx, `SELECT `+groupCols+` FROM groups g WHERE g.space_id=$1 AND g.user_id=$2 AND g.archived_at IS NULL ORDER BY g.created_at`, spaceID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Group
	for rows.Next() {
		g, err := scanGroup(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, g := range out {
		if last, _ := s.ListGroupMessages(ctx, g.ID, nil, 1); len(last) == 1 {
			g.LastMessage = last[0]
		}
	}
	return out, nil
}

// GroupFor loads a group the person made. Anyone else's is ErrNotFound.
func (s *Store) GroupFor(ctx context.Context, spaceID, userID, id string) (*Group, error) {
	return scanGroup(s.DB.QueryRow(ctx, `SELECT `+groupCols+` FROM groups g WHERE g.id=$1 AND g.space_id=$2 AND g.user_id=$3 AND g.archived_at IS NULL`, id, spaceID, userID))
}

// GetGroup loads a group without an owner check; server-side use only.
func (s *Store) GetGroup(ctx context.Context, spaceID, id string) (*Group, error) {
	return scanGroup(s.DB.QueryRow(ctx, `SELECT `+groupCols+` FROM groups g WHERE g.id=$1 AND g.space_id=$2 AND g.archived_at IS NULL`, id, spaceID))
}

// CreateGroup makes a group of bots for one person. The caller checks that
// the person can use every bot; this checks that they're all in the space.
func (s *Store) CreateGroup(ctx context.Context, spaceID, userID, name string, botIDs []string) (*Group, error) {
	var g *Group
	err := pgx.BeginFunc(ctx, s.DB, func(tx pgx.Tx) error {
		var n int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM bots WHERE space_id=$1 AND archived_at IS NULL AND id = ANY($2::uuid[])`, spaceID, botIDs).Scan(&n); err != nil {
			return err
		}
		if n != len(botIDs) {
			return ErrNotFound
		}
		var id string
		if err := tx.QueryRow(ctx, `INSERT INTO groups(space_id,user_id,name) VALUES($1,$2,$3) RETURNING id`, spaceID, userID, name).Scan(&id); err != nil {
			return err
		}
		for i, b := range botIDs {
			if _, err := tx.Exec(ctx, `INSERT INTO group_members(group_id,bot_id,position) VALUES($1,$2,$3)`, id, b, i); err != nil {
				return err
			}
		}
		var err error
		g, err = scanGroup(tx.QueryRow(ctx, `SELECT `+groupCols+` FROM groups g WHERE g.id=$1`, id))
		return err
	})
	return g, err
}

func (s *Store) ArchiveGroup(ctx context.Context, spaceID, userID, id string) error {
	t, err := s.DB.Exec(ctx, `UPDATE groups SET archived_at=now() WHERE id=$1 AND space_id=$2 AND user_id=$3 AND archived_at IS NULL`, id, spaceID, userID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}
