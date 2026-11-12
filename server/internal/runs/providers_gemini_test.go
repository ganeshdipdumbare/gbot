package runs

import "testing"

func TestGeminiRoot(t *testing.T) {
	for in, want := range map[string]string{
		"https://generativelanguage.googleapis.com/v1beta":  "https://generativelanguage.googleapis.com",
		"https://generativelanguage.googleapis.com/v1beta/": "https://generativelanguage.googleapis.com",
		"https://proxy.example.com/gemini":                  "https://proxy.example.com/gemini",
	} {
		if got := geminiRoot(in); got != want {
			t.Errorf("geminiRoot(%q) = %q, want %q", in, got, want)
		}
	}
}
