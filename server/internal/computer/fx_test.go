package computer

import (
	"bytes"
	"os"
	"strings"
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

// Both browsers report what sits on top of a page (a cookie banner, a
// dialog) with the same rules, so a bot reads the computer and the phone
// alike. The computer has it as LAYERS_JS in web.py, the phone as layers()
// in gbot-read.js; the bodies must match line for line, indent aside.
func TestPhoneLayersMatchComputerLayers(t *testing.T) {
	web, err := os.ReadFile("../../../infra/sandboxes/computer/web.py")
	if err != nil {
		t.Skip("repository layout not available:", err)
	}
	read, err := os.ReadFile("../../../apps/android/app/src/main/assets/gbot-read.js")
	if err != nil {
		t.Fatal(err)
	}
	body := func(src, start, end string) []string {
		s := string(src)
		i := strings.Index(s, start)
		if i < 0 {
			t.Fatalf("no %q", start)
		}
		s = s[i+len(start):]
		j := strings.Index(s, end)
		if j < 0 {
			t.Fatalf("no end %q after %q", end, start)
		}
		var lines []string
		for _, l := range strings.Split(s[:j], "\n") {
			if l = strings.TrimSpace(l); l != "" {
				lines = append(lines, l)
			}
		}
		return lines
	}
	computer := body(string(web), "LAYERS_JS = r\"\"\"() => {", "return out;")
	phone := body(string(read), "function layers() {", "return out;")
	if strings.Join(computer, "\n") != strings.Join(phone, "\n") {
		t.Fatalf("layers() in gbot-read.js differs from LAYERS_JS in web.py:\ncomputer:\n%s\n\nphone:\n%s",
			strings.Join(computer, "\n"), strings.Join(phone, "\n"))
	}
}
