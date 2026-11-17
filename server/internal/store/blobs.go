package store

import (
	"context"
	"errors"
)

// A blob is bytes a tool handed back for the person to look at: a diagram
// from an integration, a screenshot, a picture. Messages point at one by
// id; the API serves it to whoever may see that bot's chat.
type Blob struct {
	ID    string
	BotID string
	User  string
	Mime  string
	Bytes []byte
}

// MaxBlob is the largest picture gbot keeps. A tool that hands back more
// is left as text: the chat is not a file store.
const MaxBlob = 8 << 20

// AddBlob saves bytes and returns their id.
func (s *Store) AddBlob(ctx context.Context, b *Blob) (string, error) {
	if len(b.Bytes) == 0 {
		return "", errors.New("empty blob")
	}
	if len(b.Bytes) > MaxBlob {
		return "", errors.New("blob too large")
	}
	var id string
	err := s.DB.QueryRow(ctx,
		`INSERT INTO blobs (bot_id, user_id, mime, bytes) VALUES ($1, $2, $3, $4) RETURNING id`,
		nullable(b.BotID), nullable(b.User), b.Mime, b.Bytes).Scan(&id)
	return id, err
}

// Blob reads one back. The caller checks who may see it.
func (s *Store) Blob(ctx context.Context, id string) (*Blob, error) {
	var b Blob
	var bot, user *string
	err := s.DB.QueryRow(ctx, `SELECT id, bot_id, user_id, mime, bytes FROM blobs WHERE id = $1`, id).
		Scan(&b.ID, &bot, &user, &b.Mime, &b.Bytes)
	if err != nil {
		return nil, notFound(err)
	}
	if bot != nil {
		b.BotID = *bot
	}
	if user != nil {
		b.User = *user
	}
	return &b, nil
}
