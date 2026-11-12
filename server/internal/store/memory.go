package store

import (
	"context"
	"strings"

	"github.com/ganeshdipdumbare/kritrim/memory"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Memory is the agent's long-term memory, scoped to the bot and person
// pair on the run's context.
type Memory struct{ DB *pgxpool.Pool }

func NewMemory(s *Store) *Memory { return &Memory{DB: s.DB} }

type scopeKey struct{}

// WithScope names the memory namespace for a run.
func WithScope(ctx context.Context, ns string) context.Context {
	return context.WithValue(ctx, scopeKey{}, ns)
}

func (m *Memory) ns(ctx context.Context) string {
	if ns, _ := ctx.Value(scopeKey{}).(string); ns != "" {
		return ns
	}
	return "default"
}

func (m *Memory) Put(ctx context.Context, key, value string) error {
	_, err := m.DB.Exec(ctx, `INSERT INTO agent_memory(ns, key, value) VALUES($1,$2,$3)
		ON CONFLICT (ns, key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()`, m.ns(ctx), key, value)
	return err
}

func (m *Memory) Get(ctx context.Context, key string) (string, bool) {
	var value string
	err := m.DB.QueryRow(ctx, `SELECT value FROM agent_memory WHERE ns=$1 AND key=$2`, m.ns(ctx), key).Scan(&value)
	if err != nil {
		return "", false
	}
	return value, true
}

func (m *Memory) Search(ctx context.Context, query string, limit int) ([]memory.Item, error) {
	if limit <= 0 {
		limit = 5
	}
	pattern := "%" + escapeLike(query) + "%"
	rows, err := m.DB.Query(ctx, `SELECT key, value, updated_at FROM agent_memory
		WHERE ns=$1 AND (key ILIKE $2 ESCAPE '\' OR value ILIKE $2 ESCAPE '\')
		ORDER BY updated_at DESC LIMIT $3`, m.ns(ctx), pattern, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []memory.Item
	for rows.Next() {
		var it memory.Item
		if err := rows.Scan(&it.Key, &it.Value, &it.Updated); err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

func (m *Memory) Delete(ctx context.Context, key string) error {
	_, err := m.DB.Exec(ctx, `DELETE FROM agent_memory WHERE ns=$1 AND key=$2`, m.ns(ctx), key)
	return err
}

func escapeLike(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

var _ memory.Store = (*Memory)(nil)
