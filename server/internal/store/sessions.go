package store

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/ganeshdipdumbare/kritrim"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Sessions stores model conversations in Postgres for the agent runtime.
// Save is compare-and-swap on Version, so two writers can't overwrite
// each other.
type Sessions struct{ DB *pgxpool.Pool }

func NewSessions(s *Store) *Sessions { return &Sessions{DB: s.DB} }

func (s *Sessions) Load(ctx context.Context, id string) (*kritrim.Session, error) {
	var raw []byte
	var version int64
	err := s.DB.QueryRow(ctx, `SELECT state, version FROM agent_sessions WHERE id=$1`, id).Scan(&raw, &version)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var sess kritrim.Session
	if err := json.Unmarshal(raw, &sess); err != nil {
		return nil, err
	}
	sess.ID = id
	sess.Version = version
	return &sess, nil
}

func (s *Sessions) Save(ctx context.Context, sess *kritrim.Session) error {
	if sess.ID == "" {
		return errors.New("store: session has no id")
	}
	raw, err := json.Marshal(sess)
	if err != nil {
		return err
	}
	var version int64
	if sess.Version == 0 {
		err = s.DB.QueryRow(ctx, `INSERT INTO agent_sessions(id, version, state) VALUES($1, 1, $2)
			ON CONFLICT (id) DO NOTHING RETURNING version`, sess.ID, raw).Scan(&version)
	} else {
		err = s.DB.QueryRow(ctx, `UPDATE agent_sessions SET version=version+1, state=$2, updated_at=now()
			WHERE id=$1 AND version=$3 RETURNING version`, sess.ID, raw, sess.Version).Scan(&version)
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return kritrim.ErrSessionConflict
	}
	if err != nil {
		return err
	}
	sess.Version = version
	return nil
}

var _ kritrim.SessionStore = (*Sessions)(nil)
