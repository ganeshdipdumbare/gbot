package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

type MCPServer struct {
	ID        string    `json:"id"`
	UserID    string    `json:"-"`
	Name      string    `json:"name"`
	URL       string    `json:"url"`
	HasAuth   bool      `json:"hasAuth"`
	Enabled   bool      `json:"enabled"`
	CreatedAt time.Time `json:"createdAt"`
	Sealed    []byte    `json:"-"` // encrypted headers
	OAuth     []byte    `json:"-"` // encrypted OAuth tokens
}

func scanMCP(row pgx.Row) (*MCPServer, error) {
	m := &MCPServer{}
	if err := row.Scan(&m.ID, &m.UserID, &m.Name, &m.URL, &m.Sealed, &m.OAuth, &m.Enabled, &m.CreatedAt); err != nil {
		return nil, notFound(err)
	}
	m.HasAuth = len(m.Sealed) > 0 || len(m.OAuth) > 0
	return m, nil
}

const mcpCols = `id, user_id, name, url, headers, oauth, enabled, created_at`

func (s *Store) collectMCP(ctx context.Context, q string, args ...any) ([]*MCPServer, error) {
	rows, err := s.DB.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*MCPServer{}
	for rows.Next() {
		m, err := scanMCP(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) ListMCP(ctx context.Context, userID string) ([]*MCPServer, error) {
	return s.collectMCP(ctx, `SELECT `+mcpCols+` FROM mcp_servers WHERE user_id=$1 ORDER BY created_at`, userID)
}

func (s *Store) AllEnabledMCP(ctx context.Context) ([]*MCPServer, error) {
	return s.collectMCP(ctx, `SELECT `+mcpCols+` FROM mcp_servers WHERE enabled`)
}

func (s *Store) CreateMCP(ctx context.Context, userID, name, url string, sealed []byte) (*MCPServer, error) {
	return scanMCP(s.DB.QueryRow(ctx, `INSERT INTO mcp_servers(user_id,name,url,headers) VALUES($1,$2,$3,$4) RETURNING `+mcpCols, userID, name, url, sealed))
}

func (s *Store) SetMCPEnabled(ctx context.Context, userID, id string, on bool) (*MCPServer, error) {
	return scanMCP(s.DB.QueryRow(ctx, `UPDATE mcp_servers SET enabled=$3 WHERE id=$1 AND user_id=$2 RETURNING `+mcpCols, id, userID, on))
}

// SetMCPOAuth stores sealed tokens after a sign-in or a refresh.
func (s *Store) SetMCPOAuth(ctx context.Context, userID, id string, sealed []byte) (*MCPServer, error) {
	return scanMCP(s.DB.QueryRow(ctx, `UPDATE mcp_servers SET oauth=$3 WHERE id=$1 AND user_id=$2 RETURNING `+mcpCols, id, userID, sealed))
}

func (s *Store) DeleteMCP(ctx context.Context, userID, id string) error {
	t, err := s.DB.Exec(ctx, `DELETE FROM mcp_servers WHERE id=$1 AND user_id=$2`, id, userID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}

type Skill struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Description string    `json:"description"`
	Body        string    `json:"body"`
	CreatedAt   time.Time `json:"createdAt"`
}

func (s *Store) ListSkills(ctx context.Context, userID string) ([]*Skill, error) {
	rows, err := s.DB.Query(ctx, `SELECT id, name, description, body, created_at FROM skills WHERE user_id=$1 ORDER BY name`, userID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (*Skill, error) {
		k := &Skill{}
		return k, r.Scan(&k.ID, &k.Name, &k.Description, &k.Body, &k.CreatedAt)
	})
}

func (s *Store) UpsertSkill(ctx context.Context, userID string, k *Skill) (*Skill, error) {
	out := &Skill{}
	err := s.DB.QueryRow(ctx, `INSERT INTO skills(user_id,name,description,body) VALUES($1,$2,$3,$4)
		ON CONFLICT (user_id,name) DO UPDATE SET description=EXCLUDED.description, body=EXCLUDED.body
		RETURNING id, name, description, body, created_at`, userID, k.Name, k.Description, k.Body).
		Scan(&out.ID, &out.Name, &out.Description, &out.Body, &out.CreatedAt)
	return out, err
}

func (s *Store) DeleteSkill(ctx context.Context, userID, id string) error {
	t, err := s.DB.Exec(ctx, `DELETE FROM skills WHERE id=$1 AND user_id=$2`, id, userID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}
