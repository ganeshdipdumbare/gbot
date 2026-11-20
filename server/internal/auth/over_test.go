package auth

import (
	"testing"
	"time"
)

func TestRefundGivesBackAnAttempt(t *testing.T) {
	l := NewLimiter(1, time.Minute)
	ok, tk := l.Take("a")
	if !ok {
		t.Fatal("first attempt refused")
	}
	l.Refund(tk)
	if !l.Allow("a") {
		t.Fatal("a refunded attempt still counted")
	}
	if l.Allow("a") {
		t.Fatal("over the limit allowed")
	}
}

// A refund for an attempt counted in a window that has since been replaced
// must not take a hit away from the new one, however close in time.
func TestRefundLeavesAReplacedWindowAlone(t *testing.T) {
	l := NewLimiter(1, time.Minute)
	_, old := l.Take("a")
	// The window ends and another attempt opens a new one.
	l.mu.Lock()
	l.hits["a"].start = time.Now().Add(-2 * time.Minute)
	l.mu.Unlock()
	if !l.Allow("a") {
		t.Fatal("new window refused its first attempt")
	}
	l.Refund(old)
	if l.Allow("a") {
		t.Fatal("a refund for the old window freed a slot in the new one")
	}
}
