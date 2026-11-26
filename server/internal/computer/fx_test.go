package computer

import (
	"bytes"
	"os"
	"testing"
)

// The bot's visible cursor (fx.js) runs on the computer and in the phone's
// browser. The phone app can only bundle a copy, so the copy must not drift.
func TestPhoneCursorMatchesComputerCursor(t *testing.T) {
	computer, err := os.ReadFile("../../../infra/sandboxes/computer/fx.js")
	if err != nil {
		t.Skip("repository layout not available:", err)
	}
	phone, err := os.ReadFile("../../../apps/android/app/src/main/assets/gbot-fx.js")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(computer, phone) {
		t.Fatal("apps/android/app/src/main/assets/gbot-fx.js differs from infra/sandboxes/computer/fx.js: copy the computer's file over it")
	}
}
