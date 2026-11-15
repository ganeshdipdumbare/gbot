// Package crypto seals small secrets (integration tokens) at rest with
// AES-256-GCM. The key comes from GBOT_SECRET_KEY (base64, 32 bytes) or
// a 0600 key file created on first start.
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
)

type Sealer struct {
	aead cipher.AEAD
	key  []byte
}

// Derive is a key for one other purpose, so nothing else ever uses the
// sealing key itself.
func (s *Sealer) Derive(label string) []byte {
	mac := hmac.New(sha256.New, s.key)
	mac.Write([]byte(label))
	return mac.Sum(nil)
}

func Load(keyFile string) (*Sealer, error) {
	var key []byte
	if v := os.Getenv("GBOT_SECRET_KEY"); v != "" {
		k, err := base64.StdEncoding.DecodeString(v)
		if err != nil || len(k) != 32 {
			return nil, errors.New("GBOT_SECRET_KEY must be 32 bytes, base64")
		}
		key = k
	} else {
		raw, err := os.ReadFile(keyFile)
		switch {
		case err == nil:
			key, err = base64.StdEncoding.DecodeString(string(raw))
			if err != nil || len(key) != 32 {
				return nil, errors.New("secret key file is corrupt: " + keyFile)
			}
		case os.IsNotExist(err):
			key = make([]byte, 32)
			if _, err := rand.Read(key); err != nil {
				return nil, err
			}
			if err := os.MkdirAll(filepath.Dir(keyFile), 0o700); err != nil {
				return nil, err
			}
			if err := os.WriteFile(keyFile, []byte(base64.StdEncoding.EncodeToString(key)), 0o600); err != nil {
				return nil, err
			}
		default:
			return nil, err
		}
	}
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

// Owner is the additional authenticated data for a value: what it is for
// and whose it is. A sealed value only opens under the same purpose and
// owner, so a blob copied to another row or account fails to open.
func Owner(purpose, ownerID string) []byte {
	return []byte("gbot:" + purpose + ":" + ownerID)
}

func (s *Sealer) Seal(plain, aad []byte) []byte {
	nonce := make([]byte, s.aead.NonceSize())
	_, _ = rand.Read(nonce)
	return s.aead.Seal(nonce, nonce, plain, aad)
}

func (s *Sealer) Open(sealed, aad []byte) ([]byte, error) {
	n := s.aead.NonceSize()
	if len(sealed) < n {
		return nil, errors.New("sealed value too short")
	}
	return s.aead.Open(nil, sealed[:n], sealed[n:], aad)
}
