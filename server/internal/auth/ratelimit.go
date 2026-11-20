package auth

import (
	"sync"
	"time"
)

// Limiter is a small fixed-window limiter keyed by e.g. client IP.
type Limiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	hits   map[string]*bucket
}

type bucket struct {
	n     int
	start time.Time
}

func NewLimiter(max int, window time.Duration) *Limiter {
	return &Limiter{max: max, window: window, hits: map[string]*bucket{}}
}

func (l *Limiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if len(l.hits) > 10000 {
		for k, b := range l.hits {
			if now.Sub(b.start) > l.window {
				delete(l.hits, k)
			}
		}
	}
	b := l.hits[key]
	if b == nil || now.Sub(b.start) > l.window {
		l.hits[key] = &bucket{1, now}
		return true
	}
	b.n++
	return b.n <= l.max
}

// Refund gives back one hit that Allow counted before time at, for an attempt
// that turned out not to count (a sign-in that succeeded). Only the window
// that hit landed in is changed: if it has since ended, or a new window
// started, there is nothing to give back.
func (l *Limiter) Refund(key string, at time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.hits[key]
	if b == nil || b.n == 0 || b.start.After(at) || time.Since(b.start) > l.window {
		return
	}
	b.n--
}
