package models

import "strings"

// Provider APIs rarely say what a model can do, so capabilities are read
// from the model's name. They are hints for filtering, not guarantees.

var nonChat = []string{
	"embedding", "embed", "tts", "imagen", "veo", "aqa", "whisper", "dall-e",
	"moderation", "lyria", "transcribe", "rerank", "-image", "robotics",
	// Specialized Gemini surfaces that show up in /models but aren't chat.
	"banana", "computer-use", "deep-research", "antigravity",
}

// IsChat reports whether a model name looks like a text-generation model.
func IsChat(name string) bool {
	n := strings.ToLower(name)
	for _, s := range nonChat {
		if strings.Contains(n, s) {
			return false
		}
	}
	return true
}

// Capabilities returns the capabilities a model name suggests: "tools"
// (function calling), "vision" (image input) and "reasoning" (thinks before
// answering).
func Capabilities(name string) []string {
	n := strings.ToLower(name)
	caps := []string{} // never null in JSON
	tools := !strings.Contains(n, "gemma") && !strings.Contains(n, "vision-preview")
	if tools {
		caps = append(caps, "tools")
	}
	vision := false
	switch {
	case strings.HasPrefix(n, "gemini"), strings.HasPrefix(n, "claude-3"), strings.HasPrefix(n, "claude-"):
		vision = true
	case strings.HasPrefix(n, "gpt-4o"), strings.HasPrefix(n, "gpt-4.1"), strings.HasPrefix(n, "gpt-4-turbo"),
		strings.HasPrefix(n, "gpt-5"), strings.HasPrefix(n, "o1"), strings.HasPrefix(n, "o3"), strings.HasPrefix(n, "o4"):
		vision = true
	}
	for _, s := range []string{"vision", "llava", "-vl", "vl-", "omni", "pixtral", "gemma-3", "gemma3"} {
		if strings.Contains(n, s) {
			vision = true
		}
	}
	if vision {
		caps = append(caps, "vision")
	}
	reasoning := strings.HasPrefix(n, "o1") || strings.HasPrefix(n, "o3") || strings.HasPrefix(n, "o4") ||
		strings.HasPrefix(n, "gpt-5") || strings.HasPrefix(n, "gemini-2.5") || strings.HasPrefix(n, "gemini-3") ||
		strings.HasPrefix(n, "claude-opus") || strings.HasPrefix(n, "claude-sonnet") || strings.HasPrefix(n, "claude-3-7")
	for _, s := range []string{"thinking", "reason", "-r1", "qwq", "deepseek-r"} {
		if strings.Contains(n, s) {
			reasoning = true
		}
	}
	if reasoning {
		caps = append(caps, "reasoning")
	}
	return caps
}

func hasCap(caps []string, c string) bool {
	for _, x := range caps {
		if x == c {
			return true
		}
	}
	return false
}

// ModelCaps maps each model name to its capabilities.
func ModelCaps(names []string) map[string][]string {
	out := make(map[string][]string, len(names))
	for _, n := range names {
		out[n] = Capabilities(n)
	}
	return out
}
