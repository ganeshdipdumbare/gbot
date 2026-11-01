package runs

import (
	"context"
	"testing"
	"time"
)

func TestPoll(t *testing.T) {
	h := NewHub()
	ctx := context.Background()

	_, next, reset := h.Poll(ctx, "s", 0, time.Second)
	if !reset || next != 1 {
		t.Fatalf("first poll: next=%d reset=%v", next, reset)
	}

	// A poll waits for the next event and returns it.
	go func() { time.Sleep(50 * time.Millisecond); h.Publish("s", Event{Type: "a"}) }()
	evs, next, reset := h.Poll(ctx, "s", next, 2*time.Second)
	if len(evs) != 1 || reset || next != 2 {
		t.Fatalf("waiting poll: %d events next=%d reset=%v", len(evs), next, reset)
	}

	// Events queued between polls all come back, in order.
	h.Publish("s", Event{Type: "b"})
	h.Publish("s", Event{Type: "c"})
	evs, next, _ = h.Poll(ctx, "s", next, time.Second)
	if len(evs) != 2 || string(evs[0])[:12] != `{"type":"b"}`[:12] || next != 4 {
		t.Fatalf("batch: %s next=%d", evs, next)
	}

	// Other spaces never see them.
	if evs, _, _ := h.Poll(ctx, "other", 1, 10*time.Millisecond); len(evs) != 0 {
		t.Fatal("leaked across spaces")
	}

	// A client that fell behind the backlog is told to refetch.
	for i := 0; i < backlogSize+5; i++ {
		h.Publish("s", Event{Type: "x"})
	}
	if _, _, reset := h.Poll(ctx, "s", next, time.Second); !reset {
		t.Fatal("expected reset after falling behind")
	}

	// A sequence from before a server restart resets too.
	if _, _, reset := h.Poll(ctx, "s", 1<<40, time.Second); !reset {
		t.Fatal("expected reset for a future sequence")
	}

	// An idle poll times out empty.
	_, n, _ := h.Poll(ctx, "idle", 1, 30*time.Millisecond)
	if n != 1 {
		t.Fatalf("idle next=%d", n)
	}
}
