// Package runs turns chat messages into agent runs and fans their
// progress out live to every open client in the space.
package runs

import (
	"context"
	"encoding/json"
	"sync"
	"time"
)

// Event is what clients receive over SSE.
type Event struct {
	Type    string `json:"type"` // presence | message | message_update | delta | message_done | bot | bot_removed | group | computer
	BotID   string `json:"botId,omitempty"`
	GroupID string `json:"groupId,omitempty"`
	Data    any    `json:"data,omitempty"`
}

// Hub fans events out per space. Slow subscribers are dropped rather than
// allowed to block a run; their client reconnects and refetches.
//
// It also keeps a short numbered backlog per space for long-polling
// clients, whose network path can't hold a stream open (some proxies and
// tunnels buffer server-sent events until the response ends).
type Hub struct {
	mu   sync.Mutex
	subs map[string]map[chan []byte]struct{}
	logs map[string]*backlog
}

type backlog struct {
	next   int64 // sequence number of the next event
	events []logged
	wake   chan struct{} // closed when an event arrives
}

type logged struct {
	seq  int64
	data json.RawMessage
}

const backlogSize = 2000

func NewHub() *Hub {
	return &Hub{subs: map[string]map[chan []byte]struct{}{}, logs: map[string]*backlog{}}
}

func (h *Hub) log(space string) *backlog {
	l := h.logs[space]
	if l == nil {
		l = &backlog{next: 1, wake: make(chan struct{})}
		h.logs[space] = l
	}
	return l
}

// Poll returns events after the given sequence number, waiting up to
// wait for one to arrive. reset is true when the caller missed events
// that left the backlog (or after=0 on first call) and should refetch.
func (h *Hub) Poll(ctx context.Context, space string, after int64, wait time.Duration) (events []json.RawMessage, next int64, reset bool) {
	deadline := time.NewTimer(wait)
	defer deadline.Stop()
	for {
		h.mu.Lock()
		l := h.log(space)
		if after <= 0 || after > l.next {
			next := l.next
			h.mu.Unlock()
			return nil, next, true
		}
		if len(l.events) > 0 && after < l.events[0].seq {
			reset = true
		}
		for _, e := range l.events {
			if e.seq >= after {
				events = append(events, e.data)
			}
		}
		next, wake := l.next, l.wake
		h.mu.Unlock()
		if len(events) > 0 || reset {
			return events, next, reset
		}
		select {
		case <-wake:
		case <-deadline.C:
			return nil, next, false
		case <-ctx.Done():
			return nil, next, false
		}
	}
}

func (h *Hub) Subscribe(space string) (chan []byte, func()) {
	ch := make(chan []byte, 256)
	h.mu.Lock()
	if h.subs[space] == nil {
		h.subs[space] = map[chan []byte]struct{}{}
	}
	h.subs[space][ch] = struct{}{}
	h.mu.Unlock()
	return ch, func() {
		h.mu.Lock()
		if _, ok := h.subs[space][ch]; ok {
			delete(h.subs[space], ch)
			close(ch)
		}
		h.mu.Unlock()
	}
}

func (h *Hub) Publish(space string, e Event) {
	b, err := json.Marshal(e)
	if err != nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	l := h.log(space)
	l.events = append(l.events, logged{l.next, b})
	if len(l.events) > backlogSize {
		l.events = l.events[len(l.events)-backlogSize:]
	}
	l.next++
	close(l.wake)
	l.wake = make(chan struct{})
	for ch := range h.subs[space] {
		select {
		case ch <- b:
		default:
			delete(h.subs[space], ch)
			close(ch)
		}
	}
}
