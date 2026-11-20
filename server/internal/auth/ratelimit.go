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

// Over reports whether key has used up its window, without counting this
// call. With Allow called only on failures, it limits failed attempts.
func (l *Limiter) Over(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.hits[key]
	return b != nil && time.Since(b.start) <= l.window && b.n >= l.max
}

// Refund gives back one hit counted by Allow, for an attempt that turned
// out not to count (a sign-in that succeeded).
func (l *Limiter) Refund(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if b := l.hits[key]; b != nil && b.n > 0 {
		b.n--
	}
}
