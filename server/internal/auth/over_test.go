package auth

import (
	"testing"
	"time"
)

func TestRefundGivesBackAnAttempt(t *testing.T) {
	l := NewLimiter(1, time.Minute)
	if !l.Allow("a") {
		t.Fatal("first attempt refused")
	}
	l.Refund("a", time.Now())
	if !l.Allow("a") {
		t.Fatal("a refunded attempt still counted")
	}
	if l.Allow("a") {
		t.Fatal("over the limit allowed")
	}
}

// A refund for an attempt counted in an earlier window must not take a hit
// away from the window that replaced it.
func TestRefundLeavesANewerWindowAlone(t *testing.T) {
	l := NewLimiter(1, time.Minute)
	before := time.Now().Add(-2 * time.Minute)
	l.Allow("a") // a window that started after the refunded attempt
	l.Refund("a", before)
	if l.Allow("a") {
		t.Fatal("a refund from an older window freed a slot in the new one")
	}
}
