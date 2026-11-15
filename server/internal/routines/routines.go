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
	"strconv"
	"strings"
	"sync"
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
	// OnChange tells the creator's open clients that the list moved.
	OnChange func(botID, userID string)
}

// Changed tells the creator's clients to reload their routine list. It is
// called by the API handlers that edit a routine directly.
func (s *Service) Changed(botID, userID string) {
	if s.OnChange != nil {
		s.OnChange(botID, userID)
	}
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
		if next.IsZero() {
			return nil, "", errors.Join(ErrInvalid, errors.New("that schedule never comes round; check the day and month"))
		}
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
	if err == nil {
		s.Changed(botID, userID)
	}
	return out, secret, err
}

// Update changes a routine's name, prompt or schedule. Empty values keep
// the current field.
func (s *Service) Update(ctx context.Context, botID, userID, id, name, prompt, schedule string) (*store.Routine, error) {
	cur, err := s.Store.GetRoutine(ctx, botID, userID, id)
	if err != nil {
		return nil, ErrInvalid
	}
	name, prompt, schedule = strings.TrimSpace(name), strings.TrimSpace(prompt), strings.TrimSpace(schedule)
	if name != "" && (utf8.RuneCountInString(name) > 80) {
		return nil, errors.Join(ErrInvalid, errors.New("keep the name under 80 characters"))
	}
	if prompt != "" && len(prompt) > 8000 {
		return nil, errors.Join(ErrInvalid, errors.New("keep the instruction under 8000 characters"))
	}
	var next *time.Time
	if schedule != "" {
		if cur.Kind != "schedule" {
			return nil, errors.Join(ErrInvalid, errors.New("a webhook routine has no schedule"))
		}
		sc, err := cron.Parse(schedule)
		if err != nil {
			return nil, errors.Join(ErrInvalid, err)
		}
		n := sc.Next(time.Now())
		if n.IsZero() {
			return nil, errors.Join(ErrInvalid, errors.New("that schedule never comes round; check the day and month"))
		}
		next = &n
	}
	out, err := s.Store.UpdateRoutineFields(ctx, botID, userID, id, name, prompt, schedule, next)
	if err == nil {
		s.Changed(botID, userID)
	}
	return out, err
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
		if n.IsZero() {
			return nil // never comes round again; stop rescheduling it
		}
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

// webhookWindow is how far a signed timestamp may drift from our clock.
const webhookWindow = 5 * time.Minute

// replays remembers accepted webhook signatures for as long as they could
// still pass the timestamp check.
var replays = &replayCache{seen: map[string]time.Time{}}

type replayCache struct {
	mu   sync.Mutex
	seen map[string]time.Time
}

// accept reports whether key is new, and records it if so.
func (c *replayCache) accept(key string, now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for k, until := range c.seen {
		if now.After(until) {
			delete(c.seen, k)
		}
	}
	if _, dup := c.seen[key]; dup {
		return false
	}
	c.seen[key] = now.Add(2 * webhookWindow)
	return true
}

// Verify checks a webhook call. The caller either signs the request
// (X-GBot-Signature: sha256=<hex hmac>, over "<X-GBot-Timestamp>.<body>")
// or sends the secret as a bearer token over HTTPS. The signature is bound
// to a timestamp within five minutes, so a captured request cannot be
// replayed later. The secret is never stored: signatures use sha256(secret).
func Verify(r *store.Routine, body []byte, signature, timestamp, bearer string, now time.Time) bool {
	if r.Kind != "webhook" || len(r.SecretHash()) == 0 {
		return false
	}
	if strings.HasPrefix(signature, "sha256=") {
		ts, err := strconv.ParseInt(timestamp, 10, 64)
		if err != nil {
			return false
		}
		drift := now.Sub(time.Unix(ts, 0))
		if drift < -webhookWindow || drift > webhookWindow {
			return false
		}
		mac := hmac.New(sha256.New, r.SecretHash())
		mac.Write([]byte(timestamp + "."))
		mac.Write(body)
		want := mac.Sum(nil)
		got, err := hex.DecodeString(strings.TrimPrefix(signature, "sha256="))
		if err != nil || !hmac.Equal(got, want) {
			return false
		}
		// A signature is good once. Within the window it could otherwise be
		// sent again, so each accepted one is remembered until it expires.
		return replays.accept(r.ID+"|"+signature, now)
	}
	if bearer != "" {
		h := sha256.Sum256([]byte(bearer))
		return hmac.Equal(h[:], r.SecretHash())
	}
	return false
}
