package crypto

import (
	"path/filepath"
	"testing"
)

func TestLoadCreatesOnceAndReuses(t *testing.T) {
	t.Setenv("GBOT_SECRET_KEY", "")
	path := filepath.Join(t.TempDir(), "sub", "secret.key")
	a, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	b, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	sealed := a.Seal([]byte("x"), Owner("t", "u"))
	if _, err := b.Open(sealed, Owner("t", "u")); err != nil {
		t.Fatal("second load did not reuse the first key:", err)
	}
}
