package store

import (
	"context"

	"github.com/jackc/pgx/v5"
)

// Space is a team: its members share the bots their owner shares with
// the team. Personal bots stay private to their owner.
type Space struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Role string `json:"role"` // owner | member, as seen by the viewer
}

// UserSpaces lists the spaces a person belongs to.
func (s *Store) UserSpaces(ctx context.Context, userID string) ([]Space, error) {
	rows, err := s.DB.Query(ctx, `SELECT sp.id, sp.name, m.role FROM space_members m
		JOIN spaces sp ON sp.id = m.space_id WHERE m.user_id=$1 ORDER BY sp.created_at, sp.id`, userID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[Space])
}

// CreateSpace makes a team owned by its creator.
func (s *Store) CreateSpace(ctx context.Context, userID, name string) (*Space, error) {
	sp := &Space{Name: name, Role: "owner"}
	err := pgx.BeginFunc(ctx, s.DB, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO spaces(name) VALUES($1) RETURNING id`, name).Scan(&sp.ID); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO space_members(space_id,user_id,role) VALUES($1,$2,'owner')`, sp.ID, userID)
		return err
	})
	if err != nil {
		return nil, err
	}
	return sp, nil
}

// SpaceRole returns the person's role in a space, or ErrNotFound when
// they aren't a member.
// CountOwnedSpaces counts the teams a person owns, their own included.
func (s *Store) CountOwnedSpaces(ctx context.Context, userID string) (int, error) {
	var n int
	err := s.DB.QueryRow(ctx, `SELECT count(*) FROM space_members WHERE user_id=$1 AND role='owner'`, userID).Scan(&n)
	return n, err
}

func (s *Store) SpaceRole(ctx context.Context, spaceID, userID string) (string, error) {
	var role string
	err := s.DB.QueryRow(ctx, `SELECT role FROM space_members WHERE space_id=$1 AND user_id=$2`, spaceID, userID).Scan(&role)
	return role, notFound(err)
}

// SpaceMembers lists a team's members, owners first.
func (s *Store) SpaceMembers(ctx context.Context, spaceID string) ([]Member, error) {
	rows, err := s.DB.Query(ctx, `SELECT u.id, u.email, u.name, m.role FROM space_members m
		JOIN users u ON u.id = m.user_id WHERE m.space_id=$1 ORDER BY (m.role='owner') DESC, u.name`, spaceID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Member, error) {
		var m Member
		return m, r.Scan(&m.UserID, &m.Email, &m.Name, &m.Role)
	})
}

// SpaceMemberIDs lists the user ids in a space.
func (s *Store) SpaceMemberIDs(ctx context.Context, spaceID string) ([]string, error) {
	rows, err := s.DB.Query(ctx, `SELECT user_id::text FROM space_members WHERE space_id=$1`, spaceID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[string])
}

// AddSpaceMember adds an existing account to a team.
func (s *Store) AddSpaceMember(ctx context.Context, spaceID, userID string) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO space_members(space_id,user_id,role) VALUES($1,$2,'member')
		ON CONFLICT (space_id,user_id) DO NOTHING`, spaceID, userID)
	return err
}

// RemoveSpaceMember takes a person off a team, along with their access to
// the team's bots. Owners can't be removed.
func (s *Store) RemoveSpaceMember(ctx context.Context, spaceID, userID string) error {
	return pgx.BeginFunc(ctx, s.DB, func(tx pgx.Tx) error {
		t, err := tx.Exec(ctx, `DELETE FROM space_members WHERE space_id=$1 AND user_id=$2 AND role<>'owner'`, spaceID, userID)
		if err != nil {
			return err
		}
		if t.RowsAffected() == 0 {
			return ErrNotFound
		}
		_, err = tx.Exec(ctx, `DELETE FROM bot_members WHERE user_id=$2 AND bot_id IN (SELECT id FROM bots WHERE space_id=$1)`, spaceID, userID)
		return err
	})
}
