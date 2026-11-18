package api

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"gbot/internal/auth"
	"gbot/internal/store"
)

// Speech calls are paid by the operator's key when the server uses a hosted
// speech service, so an account must not be able to send them without limit.
func TestVoiceTranscribeIsRateLimitedPerAccount(t *testing.T) {
	var upstream atomic.Int32
	stt := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstream.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"text":"hi"}`))
	}))
	defer stt.Close()

	s := &Server{Voice: VoiceConfig{STTURL: stt.URL, STTModel: "whisper-1"}}
	s.voiceLimit = auth.NewLimiter(30, time.Minute)
	user := &store.User{ID: "u-voice"}

	limited := 0
	for i := 0; i < 60; i++ {
		req := httptest.NewRequest(http.MethodPost, "/api/voice/transcribe", bytes.NewReader([]byte("audio")))
		req.Header.Set("Content-Type", "audio/webm")
		req = req.WithContext(context.WithValue(req.Context(), ctxKey{}, user))
		rec := httptest.NewRecorder()
		s.transcribe(rec, req)
		if rec.Code == http.StatusTooManyRequests {
			limited++
		}
	}
	if limited == 0 {
		t.Fatalf("60 transcribe calls from one account were all forwarded to the paid speech service (%d upstream hits)", upstream.Load())
	}
}
