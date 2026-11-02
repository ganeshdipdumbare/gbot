// Package runs turns chat messages into agent runs and fans their
// progress out live to every open client in the space.
package runs

import (
	"context"
	"encoding/json"
	"slices"
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

// Hub fans events out per space, and only to the people each event is
// for: a bot's chats are private to the person they belong to, so every
// event names its audience and a client never receives events for others.
// Slow subscribers are dropped rather than allowed to block a run; their
// client reconnects and refetches.
//
// It also keeps a short numbered backlog per space for long-polling
// clients, whose network path can't hold a stream open (some proxies and
// tunnels buffer server-sent events until the response ends).
type Hub struct {
	mu   sync.Mutex
	subs map[string]map[chan []byte]string // space -> subscriber -> user
	logs map[string]*backlog
}

type backlog struct {
	next   int64 // sequence number of the next event
	events []logged
	wake   chan struct{} // closed when an event arrives
}

type logged struct {
	seq  int64
	to   []string
	data json.RawMessage
}

const backlogSize = 2000

func NewHub() *Hub {
	return &Hub{subs: map[string]map[chan []byte]string{}, logs: map[string]*backlog{}}
}

func (h *Hub) log(space string) *backlog {
	l := h.logs[space]
	if l == nil {
		l = &backlog{next: 1, wake: make(chan struct{})}
		h.logs[space] = l
	}
	return l
}

// Poll returns the user's events after the given sequence number, waiting
// up to wait for one to arrive. reset is true when the caller missed events
// that left the backlog (or after=0 on first call) and should refetch.
func (h *Hub) Poll(ctx context.Context, space, user string, after int64, wait time.Duration) (events []json.RawMessage, next int64, reset bool) {
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
			if e.seq >= after && slices.Contains(e.to, user) {
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

// Subscribe opens a live stream of the space's events for one user.
func (h *Hub) Subscribe(space, user string) (chan []byte, func()) {
	ch := make(chan []byte, 256)
	h.mu.Lock()
	if h.subs[space] == nil {
		h.subs[space] = map[chan []byte]string{}
	}
	h.subs[space][ch] = user
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

// Publish sends an event to the users in to. An event with no audience
// goes to nobody: publishing to everyone in a space must be spelled out.
func (h *Hub) Publish(space string, to []string, e Event) {
	if len(to) == 0 {
		return
	}
	b, err := json.Marshal(e)
	if err != nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	l := h.log(space)
	l.events = append(l.events, logged{l.next, to, b})
	if len(l.events) > backlogSize {
		l.events = l.events[len(l.events)-backlogSize:]
	}
	l.next++
	close(l.wake)
	l.wake = make(chan struct{})
	for ch, user := range h.subs[space] {
		if !slices.Contains(to, user) {
			continue
		}
		select {
		case ch <- b:
		default:
			delete(h.subs[space], ch)
			close(ch)
		}
	}
}
