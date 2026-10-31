package auth

import "testing"

func TestPasswordRoundTrip(t *testing.T) {
	h, err := HashPassword("correct horse")
	if err != nil {
		t.Fatal(err)
	}
	if ok, _ := CheckPassword("correct horse", h); !ok {
		t.Fatal("right password rejected")
	}
	if ok, _ := CheckPassword("wrong", h); ok {
		t.Fatal("wrong password accepted")
	}
	if _, err := CheckPassword("x", "$bcrypt$nope"); err == nil {
		t.Fatal("malformed hash accepted")
	}
}

func TestLimiter(t *testing.T) {
	l := NewLimiter(2, 60e9)
	if !l.Allow("a") || !l.Allow("a") || l.Allow("a") {
		t.Fatal("limiter should allow exactly 2")
	}
	if !l.Allow("b") {
		t.Fatal("keys must be independent")
	}
}
