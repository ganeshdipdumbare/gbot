package store

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
)

type User struct {
	ID           string `json:"id"`
	Email        string `json:"email"`
	Name         string `json:"name"`
	DefaultModel string `json:"defaultModel"`
	SpaceID      string `json:"spaceId"`
	passwordHash string
}

func (u *User) PasswordHash() string { return u.passwordHash }

// CreateUser makes the user, their first space, and their first bot in
// one transaction, so a new account always lands on a bot to talk to.
func (s *Store) CreateUser(ctx context.Context, email, name, hash, model string, firstBot NewBot) (*User, error) {
	u := &User{Email: email, Name: name, DefaultModel: model}
	err := pgx.BeginFunc(ctx, s.DB, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO users(email,name,password_hash,default_model) VALUES($1,$2,$3,$4) RETURNING id`,
			email, name, hash, model).Scan(&u.ID); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx, `INSERT INTO spaces(name) VALUES('Personal') RETURNING id`).Scan(&u.SpaceID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO space_members(space_id,user_id) VALUES($1,$2)`, u.SpaceID, u.ID); err != nil {
			return err
		}
		look, _ := json.Marshal(firstBot.Look)
		_, err := tx.Exec(ctx, `INSERT INTO bots(space_id,owner_id,name,title,instructions,look) VALUES($1,$2,$3,$4,$5,$6)`,
			u.SpaceID, u.ID, firstBot.Name, firstBot.Title, firstBot.Instructions, look)
		return err
	})
	if err != nil {
		return nil, err
	}
	return u, nil
}

const userCols = `u.id, u.email, u.name, u.default_model, u.password_hash,
	(SELECT space_id FROM space_members m WHERE m.user_id=u.id ORDER BY space_id LIMIT 1)`

func scanUser(row pgx.Row) (*User, error) {
	u := &User{}
	if err := row.Scan(&u.ID, &u.Email, &u.Name, &u.DefaultModel, &u.passwordHash, &u.SpaceID); err != nil {
		return nil, notFound(err)
	}
	return u, nil
}

func (s *Store) UserByEmail(ctx context.Context, email string) (*User, error) {
	return scanUser(s.DB.QueryRow(ctx, `SELECT `+userCols+` FROM users u WHERE u.email=$1`, email))
}

func (s *Store) CountUsers(ctx context.Context) (n int, err error) {
	err = s.DB.QueryRow(ctx, `SELECT count(*) FROM users`).Scan(&n)
	return
}

func (s *Store) SetDefaultModel(ctx context.Context, userID, model string) error {
	_, err := s.DB.Exec(ctx, `UPDATE users SET default_model=$2 WHERE id=$1`, userID, model)
	return err
}

func hashToken(t string) []byte { h := sha256.Sum256([]byte(t)); return h[:] }

func (s *Store) CreateSession(ctx context.Context, token, userID string, ttl time.Duration) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,$3)`,
		hashToken(token), userID, time.Now().Add(ttl))
	return err
}

func (s *Store) DeleteSession(ctx context.Context, token string) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE token_hash=$1`, hashToken(token))
	return err
}

// UserBySession returns the session's user, or ErrNotFound when the
// token is unknown or expired.
func (s *Store) UserBySession(ctx context.Context, token string) (*User, error) {
	return scanUser(s.DB.QueryRow(ctx, `SELECT `+userCols+` FROM sessions s JOIN users u ON u.id=s.user_id
		WHERE s.token_hash=$1 AND s.expires_at > now()`, hashToken(token)))
}

func (s *Store) PurgeExpiredSessions(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE expires_at <= now()`)
	return err
}
