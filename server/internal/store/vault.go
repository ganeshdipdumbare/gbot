package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
)

// VaultSecret is one saved password. Sealed is AES-GCM ciphertext; never
// send it, or the value, to a client or a model.
type VaultSecret struct {
	ID        string    `json:"id"`
	UserID    string    `json:"-"`
	Name      string    `json:"name"`
	Origins   []string  `json:"origins"`
	Sealed    []byte    `json:"-"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

const vaultCols = `id, user_id, name, origins, value, created_at, updated_at`

func scanVault(row pgx.Row) (*VaultSecret, error) {
	v := &VaultSecret{}
	var origins []byte
	if err := row.Scan(&v.ID, &v.UserID, &v.Name, &origins, &v.Sealed, &v.CreatedAt, &v.UpdatedAt); err != nil {
		return nil, notFound(err)
	}
	_ = json.Unmarshal(origins, &v.Origins)
	return v, nil
}

func (s *Store) ListVault(ctx context.Context, userID string) ([]*VaultSecret, error) {
	rows, err := s.DB.Query(ctx, `SELECT `+vaultCols+` FROM vault_secrets WHERE user_id=$1 ORDER BY name`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*VaultSecret
	for rows.Next() {
		v, err := scanVault(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (s *Store) VaultSecretFor(ctx context.Context, userID, id string) (*VaultSecret, error) {
	return scanVault(s.DB.QueryRow(ctx, `SELECT `+vaultCols+` FROM vault_secrets WHERE id=$1 AND user_id=$2`, id, userID))
}

// VaultSecretByName is what the bot uses; it only gets the sealed value.
func (s *Store) VaultSecretByName(ctx context.Context, userID, name string) (*VaultSecret, error) {
	return scanVault(s.DB.QueryRow(ctx, `SELECT `+vaultCols+` FROM vault_secrets
		WHERE user_id=$1 AND lower(name)=lower($2)`, userID, name))
}

func (s *Store) CreateVaultSecret(ctx context.Context, v *VaultSecret) (*VaultSecret, error) {
	origins, _ := json.Marshal(v.Origins)
	return scanVault(s.DB.QueryRow(ctx, `INSERT INTO vault_secrets(user_id,name,origins,value)
		VALUES($1,$2,$3,$4) RETURNING `+vaultCols, v.UserID, v.Name, origins, v.Sealed))
}

func (s *Store) UpdateVaultSecret(ctx context.Context, userID, id string, name *string, origins *[]string, sealed []byte) (*VaultSecret, error) {
	var raw []byte
	if origins != nil {
		raw, _ = json.Marshal(*origins)
	}
	return scanVault(s.DB.QueryRow(ctx, `UPDATE vault_secrets SET
		name=COALESCE($3,name), origins=COALESCE($4::jsonb,origins),
		value=CASE WHEN $5::bytea IS NOT NULL THEN $5 ELSE value END, updated_at=now()
		WHERE id=$1 AND user_id=$2 RETURNING `+vaultCols, id, userID, name, raw, sealed))
}

func (s *Store) DeleteVaultSecret(ctx context.Context, userID, id string) error {
	t, err := s.DB.Exec(ctx, `DELETE FROM vault_secrets WHERE id=$1 AND user_id=$2`, id, userID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}
