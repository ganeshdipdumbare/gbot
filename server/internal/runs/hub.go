// Package runs turns chat messages into agent runs and fans their
// progress out live to every open client in the space.
package runs

import (
	"encoding/json"
	"sync"
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
type Hub struct {
	mu   sync.Mutex
	subs map[string]map[chan []byte]struct{}
}

func NewHub() *Hub { return &Hub{subs: map[string]map[chan []byte]struct{}{}} }

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
	for ch := range h.subs[space] {
		select {
		case ch <- b:
		default:
			delete(h.subs[space], ch)
			close(ch)
		}
	}
}
