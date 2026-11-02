// Package routines runs prompts on a schedule or when a webhook fires.
// Schedules are claimed with SKIP LOCKED, so several replicas can run
// the scheduler without running a routine twice.
package routines

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"log/slog"
	"strings"
	"time"
	"unicode/utf8"

	"gbot/internal/cron"
	"gbot/internal/store"
)

type Service struct {
	Store *store.Store
	Log   *slog.Logger
	// Fire starts a routine run on its bot, for the routine's creator.
	Fire func(ctx context.Context, bot *store.Bot, r *store.Routine, input string)
}

var ErrInvalid = errors.New("invalid routine")

// Create validates and saves a routine. For webhooks it returns the
// secret, which is shown once and only its hash is kept.
func (s *Service) Create(ctx context.Context, botID, userID, name, prompt, kind, schedule string) (*store.Routine, string, error) {
	name, prompt = strings.TrimSpace(name), strings.TrimSpace(prompt)
	if name == "" || utf8.RuneCountInString(name) > 80 {
		return nil, "", errors.Join(ErrInvalid, errors.New("give the routine a short name"))
	}
	if prompt == "" || len(prompt) > 8000 {
		return nil, "", errors.Join(ErrInvalid, errors.New("say what the routine should do"))
	}
	r := &store.Routine{BotID: botID, UserID: userID, Name: name, Prompt: prompt, Kind: kind}
	var secret string
	var hash []byte
	switch kind {
	case "schedule":
		sc, err := cron.Parse(schedule)
		if err != nil {
			return nil, "", errors.Join(ErrInvalid, err)
		}
		next := sc.Next(time.Now())
		r.Schedule, r.NextRunAt = strings.TrimSpace(schedule), &next
	case "webhook":
		b := make([]byte, 24)
		_, _ = rand.Read(b)
		secret = "whsec_" + base64.RawURLEncoding.EncodeToString(b)
		h := sha256.Sum256([]byte(secret))
		hash = h[:]
	default:
		return nil, "", errors.Join(ErrInvalid, errors.New("kind must be schedule or webhook"))
	}
	out, err := s.Store.CreateRoutine(ctx, r, hash)
	return out, secret, err
}

// Run claims due routines every few seconds until ctx ends.
func (s *Service) Run(ctx context.Context) {
	t := time.NewTicker(10 * time.Second)
	defer t.Stop()
	for {
		s.tick(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (s *Service) tick(ctx context.Context) {
	now := time.Now()
	due, err := s.Store.ClaimDue(ctx, now, func(r *store.Routine) *time.Time {
		sc, err := cron.Parse(r.Schedule)
		if err != nil {
			return nil
		}
		n := sc.Next(now)
		return &n
	})
	if err != nil {
		s.Log.Error("routines", "err", err)
		return
	}
	for _, r := range due {
		bot, err := s.Store.GetBotAnySpace(ctx, r.BotID)
		if err != nil {
			continue
		}
		s.Fire(ctx, bot, r, "")
	}
}

// Verify checks a webhook call: the caller proves it knows the secret by
// signing the body (X-GBot-Signature: sha256=<hex hmac>) or sending it as
// a bearer token over HTTPS.
func Verify(r *store.Routine, body []byte, signature, bearer string) bool {
	if r.Kind != "webhook" || len(r.SecretHash()) == 0 {
		return false
	}
	// The secret itself isn't stored, so a signature is checked against
	// a key derived from it: callers sign with sha256(secret).
	if strings.HasPrefix(signature, "sha256=") {
		mac := hmac.New(sha256.New, r.SecretHash())
		mac.Write(body)
		want := mac.Sum(nil)
		got, err := hex.DecodeString(strings.TrimPrefix(signature, "sha256="))
		return err == nil && hmac.Equal(got, want)
	}
	if bearer != "" {
		h := sha256.Sum256([]byte(bearer))
		return hmac.Equal(h[:], r.SecretHash())
	}
	return false
}
