package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

type Routine struct {
	ID        string     `json:"id"`
	BotID     string     `json:"botId"`
	Name      string     `json:"name"`
	Prompt    string     `json:"prompt"`
	Kind      string     `json:"kind"` // schedule | webhook
	Schedule  string     `json:"schedule,omitempty"`
	Active    bool       `json:"active"`
	NextRunAt *time.Time `json:"nextRunAt,omitempty"`
	LastRunAt *time.Time `json:"lastRunAt,omitempty"`
	CreatedAt time.Time  `json:"createdAt"`
	secret    []byte
}

func (r *Routine) SecretHash() []byte { return r.secret }

const routineCols = `id, bot_id, name, prompt, kind, schedule, secret_hash, active, next_run_at, last_run_at, created_at`

func scanRoutine(row pgx.Row) (*Routine, error) {
	r := &Routine{}
	if err := row.Scan(&r.ID, &r.BotID, &r.Name, &r.Prompt, &r.Kind, &r.Schedule, &r.secret, &r.Active, &r.NextRunAt, &r.LastRunAt, &r.CreatedAt); err != nil {
		return nil, notFound(err)
	}
	return r, nil
}

func (s *Store) routines(ctx context.Context, q string, args ...any) ([]*Routine, error) {
	rows, err := s.DB.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Routine{}
	for rows.Next() {
		r, err := scanRoutine(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) ListRoutines(ctx context.Context, botID string) ([]*Routine, error) {
	return s.routines(ctx, `SELECT `+routineCols+` FROM routines WHERE bot_id=$1 ORDER BY created_at`, botID)
}

func (s *Store) GetRoutine(ctx context.Context, botID, id string) (*Routine, error) {
	return scanRoutine(s.DB.QueryRow(ctx, `SELECT `+routineCols+` FROM routines WHERE id=$1 AND bot_id=$2`, id, botID))
}

// RoutineByID is for webhooks, which authenticate by the routine's secret.
func (s *Store) RoutineByID(ctx context.Context, id string) (*Routine, error) {
	return scanRoutine(s.DB.QueryRow(ctx, `SELECT `+routineCols+` FROM routines WHERE id=$1`, id))
}

func (s *Store) CreateRoutine(ctx context.Context, r *Routine, secretHash []byte) (*Routine, error) {
	return scanRoutine(s.DB.QueryRow(ctx, `INSERT INTO routines(bot_id,name,prompt,kind,schedule,secret_hash,next_run_at)
		VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING `+routineCols, r.BotID, r.Name, r.Prompt, r.Kind, r.Schedule, secretHash, r.NextRunAt))
}

func (s *Store) UpdateRoutine(ctx context.Context, botID, id string, active *bool, next *time.Time) (*Routine, error) {
	return scanRoutine(s.DB.QueryRow(ctx, `UPDATE routines SET active=COALESCE($3,active), next_run_at=COALESCE($4,next_run_at)
		WHERE id=$1 AND bot_id=$2 RETURNING `+routineCols, id, botID, active, next))
}

func (s *Store) DeleteRoutine(ctx context.Context, botID, id string) error {
	t, err := s.DB.Exec(ctx, `DELETE FROM routines WHERE id=$1 AND bot_id=$2`, id, botID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}

// ClaimDue atomically takes due schedule routines and moves each to its
// next time (via next), so replicas never run one twice.
func (s *Store) ClaimDue(ctx context.Context, now time.Time, next func(*Routine) *time.Time) ([]*Routine, error) {
	var out []*Routine
	err := pgx.BeginFunc(ctx, s.DB, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `SELECT `+routineCols+` FROM routines WHERE active AND kind='schedule' AND next_run_at <= $1
			ORDER BY next_run_at LIMIT 50 FOR UPDATE SKIP LOCKED`, now)
		if err != nil {
			return err
		}
		for rows.Next() {
			r, err := scanRoutine(rows)
			if err != nil {
				rows.Close()
				return err
			}
			out = append(out, r)
		}
		rows.Close()
		for _, r := range out {
			if _, err := tx.Exec(ctx, `UPDATE routines SET last_run_at=$2, next_run_at=$3 WHERE id=$1`, r.ID, now, next(r)); err != nil {
				return err
			}
		}
		return nil
	})
	return out, err
}

func (s *Store) TouchRoutine(ctx context.Context, id string) {
	_, _ = s.DB.Exec(ctx, `UPDATE routines SET last_run_at=now() WHERE id=$1`, id)
}
