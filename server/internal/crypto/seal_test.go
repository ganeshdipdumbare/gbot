package crypto

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"testing"
)

func TestSealBindsToOwner(t *testing.T) {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i)
	}
	s, err := loadWithKey(key)
	if err != nil {
		t.Fatal(err)
	}
	plain := []byte("sk-secret")
	sealed := s.Seal(plain, Owner("vault", "user-a"))

	got, err := s.Open(sealed, Owner("vault", "user-a"))
	if err != nil || !bytes.Equal(got, plain) {
		t.Fatalf("owner could not open their own value: %v", err)
	}
	if _, err := s.Open(sealed, Owner("vault", "user-b")); err == nil {
		t.Fatal("value opened for a different owner")
	}
	if _, err := s.Open(sealed, Owner("provider-key", "user-a")); err == nil {
		t.Fatal("value opened for a different purpose")
	}
}

// loadWithKey builds a Sealer from a raw key, for tests.
func loadWithKey(key []byte) (*Sealer, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Sealer{aead: aead, key: key}, nil
}
