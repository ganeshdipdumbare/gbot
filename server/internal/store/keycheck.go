package store

import (
	"context"
	"crypto/hmac"
	"errors"

	"github.com/jackc/pgx/v5"
)

// ErrKeyMismatch means the sealing key is not the one these secrets were
// sealed with.
var ErrKeyMismatch = errors.New("the secret key does not match this database")

// sealedSamples are one stored sealed value from each kind of table, so the
// first check can prove the key opens real data.
var sealedSamples = []string{
	`SELECT api_key FROM model_providers WHERE api_key IS NOT NULL LIMIT 1`,
	`SELECT value FROM vault_secrets LIMIT 1`,
	`SELECT bot_token FROM slack_installs LIMIT 1`,
	`SELECT headers FROM mcp_servers WHERE headers IS NOT NULL LIMIT 1`,
	`SELECT oauth FROM mcp_servers WHERE oauth IS NOT NULL LIMIT 1`,
}

// CheckSecretKey guards the sealing key. The first time, it checks that
// `opens` accepts an existing sealed value, then records want. After that
// it refuses any other key. Without this, a changed or regenerated key only
// shows up later, as secrets that quietly stop working.
func (s *Store) CheckSecretKey(ctx context.Context, want []byte, opens func(sealed []byte) bool) error {
	var have []byte
	err := s.DB.QueryRow(ctx, `SELECT check_value FROM instance_keys WHERE name='secret'`).Scan(&have)
	if err == nil {
		if !hmac.Equal(have, want) {
			return ErrKeyMismatch
		}
		return nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	for _, q := range sealedSamples {
		var sample []byte
		switch err := s.DB.QueryRow(ctx, q).Scan(&sample); {
		case errors.Is(err, pgx.ErrNoRows):
			continue
		case err != nil:
			return err
		}
		if !opens(sample) {
			return ErrKeyMismatch
		}
		break
	}
	_, err = s.DB.Exec(ctx, `INSERT INTO instance_keys(name, check_value) VALUES('secret', $1)
		ON CONFLICT (name) DO NOTHING`, want)
	return err
}
