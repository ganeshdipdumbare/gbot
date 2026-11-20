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
	ok, _ := l.Take(key)
	return ok
}

// Ticket names the window one counted attempt landed in, so a refund goes
// back to exactly that window.
type Ticket struct {
	key string
	b   *bucket
}

// Take counts one attempt like Allow and also returns its ticket.
func (l *Limiter) Take(key string) (bool, Ticket) {
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
		b = &bucket{1, now}
		l.hits[key] = b
		return true, Ticket{key, b}
	}
	b.n++
	return b.n <= l.max, Ticket{key, b}
}

// Refund gives back the attempt a ticket counted, for one that turned out
// not to count (a sign-in that succeeded). It changes only that window: if
// the window has been replaced or has ended, there is nothing to give back.
func (l *Limiter) Refund(t Ticket) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if b := l.hits[t.key]; b != nil && b == t.b && b.n > 0 && time.Since(b.start) <= l.window {
		b.n--
	}
}
