package runs

import (
	"reflect"
	"testing"
)

func TestPlainText(t *testing.T) {
	cases := map[string]string{
		`"Who's travelling?"`:  "Who's travelling?",
		`"Just me\n2 people"`:  "Just me\n2 people",
		`line one\nline two`:   "line one\nline two",
		"  already plain  ":    "already plain",
		"keeps real\nnewlines": "keeps real\nnewlines",
		`He said "hi"`:         `He said "hi"`,
	}
	for in, want := range cases {
		if got := plainText(in); got != want {
			t.Fatalf("plainText(%q) = %q, want %q", in, got, want)
		}
	}
	if got := plainText(nil); got != "" {
		t.Fatalf("plainText(nil) = %q", got)
	}
}

func TestChoices(t *testing.T) {
	want := []any{"Just me", "2 people", "3-4 people", "Larger group"}
	if got := choices(`"Just me\n2 people\n3-4 people\nLarger group"`); !reflect.DeepEqual(got, want) {
		t.Fatalf("packed string: %v", got)
	}
	if got := choices([]any{"Tea", "Coffee"}); !reflect.DeepEqual(got, []any{"Tea", "Coffee"}) {
		t.Fatalf("list: %v", got)
	}
	if got := choices(`["Tea", "Coffee"]`); !reflect.DeepEqual(got, []any{"Tea", "Coffee"}) {
		t.Fatalf("json array: %v", got)
	}
	if got := choices("- Tea\n- Coffee\n\n"); !reflect.DeepEqual(got, []any{"Tea", "Coffee"}) {
		t.Fatalf("bullets: %v", got)
	}
	if got := choices(nil); got != nil {
		t.Fatalf("nil: %v", got)
	}
	if got := choices("a\nb\nc\nd\ne"); len(got) != 4 {
		t.Fatalf("cap: %v", got)
	}
}
