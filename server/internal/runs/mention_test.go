package runs

import "testing"

func TestMentions(t *testing.T) {
	cases := []struct {
		text, name string
		want       bool
	}{
		{"@Grok Bot can you take this?", "Grok Bot", true},
		{"over to @grok bot.", "Grok Bot", true},
		{"@Grok, thoughts?", "Grok Bot", false},
		{"ask @Alex", "Al", false},
		{"ask @Alex or @Al", "Al", true},
		{"email al@example.com", "example", false},
		{"no mention here", "Grok Bot", false},
	}
	for _, c := range cases {
		if got := Mentions(c.text, c.name); got != c.want {
			t.Errorf("Mentions(%q, %q) = %v, want %v", c.text, c.name, got, c.want)
		}
	}
}
