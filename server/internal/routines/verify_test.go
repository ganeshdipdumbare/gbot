package routines

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"testing"
	"time"

	"gbot/internal/store"
)

// routine returns a webhook whose secret is "s3cret" (stored as its hash).
func routine() *store.Routine {
	h := sha256.Sum256([]byte("s3cret"))
	return store.NewWebhookRoutine(h[:])
}

func sign(key, material []byte) string {
	mac := hmac.New(sha256.New, key)
	mac.Write(material)
	return "sha256=" + hex.EncodeToString(mac.Sum(nil))
}

func TestVerifyTimestampWindow(t *testing.T) {
	r := routine()
	key := r.SecretHash()
	body := []byte(`{"event":"x"}`)
	now := time.Now()
	ts := strconv.FormatInt(now.Unix(), 10)
	good := sign(key, append([]byte(ts+"."), body...))

	if !Verify(r, body, good, ts, "", now) {
		t.Fatal("fresh timestamped signature rejected")
	}
	if Verify(r, body, good, ts, "", now.Add(10*time.Minute)) {
		t.Fatal("replay outside the window accepted")
	}
	if Verify(r, body, good, strconv.FormatInt(now.Unix()+1, 10), "", now) {
		t.Fatal("signature accepted with a different timestamp")
	}
	// Legacy body-only signatures still verify, without a timestamp.
	if !Verify(r, body, sign(key, body), "", "", now) {
		t.Fatal("legacy body signature rejected")
	}
}
