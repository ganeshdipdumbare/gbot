package auth

import (
	"testing"
	"time"
)

// Over counts nothing itself, so a limiter fed only failures never blocks
// someone who keeps getting it right.
func TestOverCountsOnlyWhatAllowCounted(t *testing.T) {
	l := NewLimiter(2, time.Minute)
	for i := 0; i < 5; i++ {
		if l.Over("a") {
			t.Fatal("checking alone used up the window")
		}
	}
	l.Allow("a")
	l.Allow("a")
	if !l.Over("a") {
		t.Fatal("two failures should reach a limit of two")
	}
	if l.Over("b") {
		t.Fatal("another key was limited")
	}
}
