package routines

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"strings"
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
	if Verify(r, body, good, ts, "", now) {
		t.Fatal("the same signed request was accepted twice")
	}
	if Verify(r, body, good, ts, "", now.Add(10*time.Minute)) {
		t.Fatal("replay outside the window accepted")
	}
	if Verify(r, body, good, strconv.FormatInt(now.Unix()+1, 10), "", now) {
		t.Fatal("signature accepted with a different timestamp")
	}
	// A body-only signature, with no timestamp, is refused.
	if Verify(r, body, sign(key, body), "", "", now) {
		t.Fatal("signature without a timestamp accepted")
	}
}

// The replay cache keys on the MAC, so the same signature in upper-case hex
// is the same signature.
func TestVerifyReplayIgnoresHexCase(t *testing.T) {
	r := routine()
	body := []byte(`{"event":"case"}`)
	now := time.Now()
	ts := strconv.FormatInt(now.Unix(), 10)
	good := sign(r.SecretHash(), append([]byte(ts+"."), body...))
	if !Verify(r, body, good, ts, "", now) {
		t.Fatal("fresh signature rejected")
	}
	upper := "sha256=" + strings.ToUpper(strings.TrimPrefix(good, "sha256="))
	if Verify(r, body, upper, ts, "", now) {
		t.Fatal("the same signature in upper case was accepted again")
	}
}
