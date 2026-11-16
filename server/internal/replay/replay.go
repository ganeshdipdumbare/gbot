// Package replay remembers signed requests that were accepted, so the same
// signature cannot be accepted twice inside its validity window. It is per
// process: a deployment with several server processes needs a shared store.
package replay

import (
	"sync"
	"time"
)

var cache = &store{seen: map[string]time.Time{}}

type store struct {
	mu   sync.Mutex
	seen map[string]time.Time
}

// Accept reports whether key is new, and records it for ttl if so.
func Accept(key string, now time.Time, ttl time.Duration) bool {
	return cache.accept(key, now, ttl)
}

func (c *store) accept(key string, now time.Time, ttl time.Duration) bool {
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
	c.seen[key] = now.Add(ttl)
	return true
}
