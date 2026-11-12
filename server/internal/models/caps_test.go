package models

import "testing"

func TestIsChatDropsSpecializedSurfaces(t *testing.T) {
	cases := []struct {
		name string
		want bool
	}{
		{"gemini-3.5-flash", true},
		{"gemini-2.5-pro", true},
		{"gemini-tts", false},
		{"text-embedding-004", false},
		{"gemini-nano-banana-2.1", false},
		{"nano-banana-pro-preview", false},
		{"gemini-2.5-computer-use-preview-10-2025", false},
		{"deep-research-preview-04-2026", false},
		{"antigravity-preview-latest", false},
	}
	for _, c := range cases {
		if got := IsChat(c.name); got != c.want {
			t.Errorf("IsChat(%q) = %v, want %v", c.name, got, c.want)
		}
	}
}
