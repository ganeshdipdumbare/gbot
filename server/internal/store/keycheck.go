package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// ErrKeyMismatch means the sealing key is not the one this database was
// set up with.
var ErrKeyMismatch = errors.New("the secret key does not match this database")

const keyCanary = "gbot key check"

// CheckSecretKey guards the sealing key. The first start stores a sealed
// canary; every later start must open it. Without this, a changed or
// regenerated key only shows up later, as secrets that quietly stop working.
func (s *Store) CheckSecretKey(ctx context.Context, seal func([]byte) []byte, open func([]byte) ([]byte, error)) error {
	var stored []byte
	err := s.DB.QueryRow(ctx, `SELECT check_value FROM instance_keys WHERE name='secret'`).Scan(&stored)
	if errors.Is(err, pgx.ErrNoRows) {
		_, err := s.DB.Exec(ctx, `INSERT INTO instance_keys(name, check_value) VALUES('secret', $1)
			ON CONFLICT (name) DO NOTHING`, seal([]byte(keyCanary)))
		return err
	}
	if err != nil {
		return err
	}
	plain, err := open(stored)
	if err != nil || string(plain) != keyCanary {
		return ErrKeyMismatch
	}
	return nil
}
