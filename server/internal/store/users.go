package store

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

type User struct {
	ID           string `json:"id"`
	Email        string `json:"email"`
	Name         string `json:"name"`
	DefaultModel string `json:"defaultModel"`
	ApprovalMode string `json:"approvalMode"`
	SpaceID      string `json:"spaceId"`
	passwordHash string
}

func (u *User) PasswordHash() string { return u.passwordHash }

// CreateUser makes the user, their first space, and their first bot in
// one transaction, so a new account always lands on a bot to talk to.
// ErrNotFirst means the server already has accounts, so the first-account
// path is closed.
var ErrNotFirst = errors.New("this server already has accounts")

// CreateUser makes an account with its first bot. When firstOnly is set, it
// only succeeds if no accounts exist yet. The count and the insert run in
// one transaction under a lock, so two simultaneous first sign-ups cannot
// both succeed.
func (s *Store) CreateUser(ctx context.Context, email, name, hash, model string, firstBot NewBot, firstOnly bool) (*User, error) {
	u := &User{Email: email, Name: name, DefaultModel: model, ApprovalMode: "auto"}
	err := pgx.BeginFunc(ctx, s.DB, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext('gbot-create-user'))`); err != nil {
			return err
		}
		if firstOnly {
			var n int
			if err := tx.QueryRow(ctx, `SELECT count(*) FROM users`).Scan(&n); err != nil {
				return err
			}
			if n > 0 {
				return ErrNotFirst
			}
		}
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
		_, err := tx.Exec(ctx, `INSERT INTO bots(space_id,owner_id,name,title,instructions,look,main,pinned) VALUES($1,$2,$3,$4,$5,$6,$7,$7)`,
			u.SpaceID, u.ID, firstBot.Name, firstBot.Title, firstBot.Instructions, look, firstBot.Main)
		return err
	})
	if err != nil {
		return nil, err
	}
	return u, nil
}

// homeSpace picks the team a person lands in when nothing else was chosen:
// a team they own (their own first, oldest first) before any team someone
// else added them to, so being added to a team can't move them.
const homeSpace = `(SELECT m.space_id FROM space_members m JOIN spaces sp ON sp.id = m.space_id
	WHERE m.user_id=u.id ORDER BY (m.role='owner') DESC, sp.created_at, m.space_id LIMIT 1)`

const userCols = `u.id, u.email, u.name, u.default_model, u.approval_mode, u.password_hash,
	` + homeSpace

func scanUser(row pgx.Row) (*User, error) {
	u := &User{}
	if err := row.Scan(&u.ID, &u.Email, &u.Name, &u.DefaultModel, &u.ApprovalMode, &u.passwordHash, &u.SpaceID); err != nil {
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
// token is unknown or expired. The user's team is the one the session
// picked, if they're still a member; otherwise their home team.
func (s *Store) UserBySession(ctx context.Context, token string) (*User, error) {
	return scanUser(s.DB.QueryRow(ctx, `SELECT u.id, u.email, u.name, u.default_model, u.approval_mode, u.password_hash,
		COALESCE(
			(SELECT m.space_id FROM space_members m WHERE m.user_id=u.id AND m.space_id=s.space_id),
			`+homeSpace+`)
		FROM sessions s JOIN users u ON u.id=s.user_id
		WHERE s.token_hash=$1 AND s.expires_at > now()`, hashToken(token)))
}

// SetSessionSpace makes spaceID the team this session works in.
func (s *Store) SetSessionSpace(ctx context.Context, token, spaceID string) error {
	_, err := s.DB.Exec(ctx, `UPDATE sessions SET space_id=$2 WHERE token_hash=$1`, hashToken(token), spaceID)
	return err
}

func (s *Store) PurgeExpiredSessions(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE expires_at <= now()`)
	return err
}

func (s *Store) UserByID(ctx context.Context, id string) (*User, error) {
	return scanUser(s.DB.QueryRow(ctx, `SELECT `+userCols+` FROM users u WHERE u.id=$1`, id))
}

// OwnerOfSpace returns the space's owner, whose settings govern its bots.
func (s *Store) OwnerOfSpace(ctx context.Context, spaceID string) (*User, error) {
	return scanUser(s.DB.QueryRow(ctx, `SELECT `+userCols+` FROM space_members m JOIN users u ON u.id=m.user_id
		WHERE m.space_id=$1 AND m.role='owner' LIMIT 1`, spaceID))
}

func (s *Store) SetApprovalMode(ctx context.Context, userID, mode string) error {
	_, err := s.DB.Exec(ctx, `UPDATE users SET approval_mode=$2 WHERE id=$1`, userID, mode)
	return err
}

type ToolRule struct {
	Tool string `json:"tool"`
	Rule string `json:"rule"` // allow | block
}

func (s *Store) ToolRules(ctx context.Context, userID string) ([]ToolRule, error) {
	rows, err := s.DB.Query(ctx, `SELECT tool, rule FROM tool_rules WHERE user_id=$1 ORDER BY created_at`, userID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (ToolRule, error) {
		var t ToolRule
		return t, r.Scan(&t.Tool, &t.Rule)
	})
}

func (s *Store) SetToolRule(ctx context.Context, userID, tool, rule string) error {
	if rule == "" {
		_, err := s.DB.Exec(ctx, `DELETE FROM tool_rules WHERE user_id=$1 AND tool=$2`, userID, tool)
		return err
	}
	_, err := s.DB.Exec(ctx, `INSERT INTO tool_rules(user_id,tool,rule) VALUES($1,$2,$3)
		ON CONFLICT (user_id,tool) DO UPDATE SET rule=EXCLUDED.rule`, userID, tool, rule)
	return err
}
