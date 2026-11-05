package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
)

// Provider is a model provider a person connected to their account. Keys
// are sealed with the server's secret key; Discovered and Manual are the
// effective model list (a deduped union) split by where they came from.
type Provider struct {
	ID         string    `json:"id"`
	UserID     string    `json:"-"`
	Name       string    `json:"name"`
	Kind       string    `json:"kind"` // openai | anthropic | gemini
	Prefix     string    `json:"-"`
	BaseURL    string    `json:"baseUrl"`
	HasKey     bool      `json:"hasKey"`
	Local      bool      `json:"local"`
	Discovered []string  `json:"discovered"`
	Manual     []string  `json:"manual"`
	Enabled    bool      `json:"enabled"`
	CreatedAt  time.Time `json:"createdAt"`
	Sealed     []byte    `json:"-"` // encrypted API key
}

// Models is the deduped union of discovered and manually entered models.
func (p *Provider) Models() []string {
	seen := make(map[string]bool, len(p.Discovered)+len(p.Manual))
	out := make([]string, 0, len(p.Discovered)+len(p.Manual))
	for _, list := range [][]string{p.Discovered, p.Manual} {
		for _, m := range list {
			if m == "" || seen[m] {
				continue
			}
			seen[m] = true
			out = append(out, m)
		}
	}
	return out
}

const providerCols = `id, user_id, name, kind, prefix, base_url, api_key, local, discovered, manual, enabled, created_at`

func scanProvider(row pgx.Row) (*Provider, error) {
	p := &Provider{}
	var discovered, manual []byte
	if err := row.Scan(&p.ID, &p.UserID, &p.Name, &p.Kind, &p.Prefix, &p.BaseURL, &p.Sealed, &p.Local,
		&discovered, &manual, &p.Enabled, &p.CreatedAt); err != nil {
		return nil, notFound(err)
	}
	_ = json.Unmarshal(discovered, &p.Discovered)
	_ = json.Unmarshal(manual, &p.Manual)
	p.HasKey = len(p.Sealed) > 0
	return p, nil
}

func (s *Store) collectProviders(ctx context.Context, q string, args ...any) ([]*Provider, error) {
	rows, err := s.DB.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Provider{}
	for rows.Next() {
		p, err := scanProvider(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) ListProviders(ctx context.Context, userID string) ([]*Provider, error) {
	return s.collectProviders(ctx, `SELECT `+providerCols+` FROM model_providers WHERE user_id=$1 ORDER BY created_at`, userID)
}

func (s *Store) AllEnabledProviders(ctx context.Context) ([]*Provider, error) {
	return s.collectProviders(ctx, `SELECT `+providerCols+` FROM model_providers WHERE enabled ORDER BY created_at`)
}

func (s *Store) GetProvider(ctx context.Context, userID, id string) (*Provider, error) {
	return scanProvider(s.DB.QueryRow(ctx, `SELECT `+providerCols+` FROM model_providers WHERE id=$1 AND user_id=$2`, id, userID))
}

func (s *Store) CreateProvider(ctx context.Context, p *Provider) (*Provider, error) {
	discovered, _ := json.Marshal(p.Discovered)
	manual, _ := json.Marshal(p.Manual)
	return scanProvider(s.DB.QueryRow(ctx, `INSERT INTO model_providers(user_id,name,kind,prefix,base_url,api_key,local,discovered,manual,enabled)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING `+providerCols,
		p.UserID, p.Name, p.Kind, p.Prefix, p.BaseURL, p.Sealed, p.Local, discovered, manual, p.Enabled))
}

// ProviderPatch updates only the fields set. Sealed replaces the key;
// ClearKey removes it. Both left zero keeps the existing key.
type ProviderPatch struct {
	Name       *string
	Kind       *string
	BaseURL    *string
	Local      *bool
	Discovered *[]string
	Manual     *[]string
	Enabled    *bool
	Sealed     []byte
	ClearKey   bool
}

func jsonList(v *[]string) []byte {
	if v == nil {
		return nil
	}
	raw, _ := json.Marshal(*v)
	return raw
}

func (s *Store) UpdateProvider(ctx context.Context, userID, id string, p ProviderPatch) (*Provider, error) {
	return scanProvider(s.DB.QueryRow(ctx, `UPDATE model_providers SET
		name=COALESCE($3,name), kind=COALESCE($4,kind), base_url=COALESCE($5,base_url),
		local=COALESCE($6,local), discovered=COALESCE($7::jsonb,discovered), manual=COALESCE($8::jsonb,manual),
		enabled=COALESCE($9,enabled),
		api_key=CASE WHEN $10 THEN NULL WHEN $11::bytea IS NOT NULL THEN $11 ELSE api_key END,
		updated_at=now()
		WHERE id=$1 AND user_id=$2 RETURNING `+providerCols,
		id, userID, p.Name, p.Kind, p.BaseURL, p.Local, jsonList(p.Discovered), jsonList(p.Manual), p.Enabled, p.ClearKey, p.Sealed))
}

func (s *Store) DeleteProvider(ctx context.Context, userID, id string) error {
	t, err := s.DB.Exec(ctx, `DELETE FROM model_providers WHERE id=$1 AND user_id=$2`, id, userID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}
