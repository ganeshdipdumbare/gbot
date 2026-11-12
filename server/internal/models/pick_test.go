package models

import "testing"

func TestPickPrefersSmallOmniModel(t *testing.T) {
	list := []Model{
		{ID: "ollama:big-model", Tools: true},
		{ID: "ollama:lfm3-omni", Tools: true},
		{ID: "ollama:another", Tools: true},
	}
	if got := pick(list, nil); got != "ollama:lfm3-omni" {
		t.Fatalf("default model = %q, want the small Omni model", got)
	}
	// An explicit choice still wins: the person may have a reason.
	if got := pick(list, []string{"ollama:another"}); got != "ollama:another" {
		t.Fatalf("explicit model = %q", got)
	}
	// A tagged variant is still recognised.
	if got := pick([]Model{{ID: "ollama:lfm3-omni:q4"}}, nil); got != "ollama:lfm3-omni:q4" {
		t.Fatalf("variant = %q", got)
	}
}

func TestMarkBestFlagsOneAndCopies(t *testing.T) {
	list := []Model{{ID: "ollama:big"}, {ID: "ollama:lfm3-omni"}}
	got := MarkBest(list)
	if got[0].Best || !got[1].Best {
		t.Fatalf("best flags = %v %v, want only the Omni model", got[0].Best, got[1].Best)
	}
	if list[1].Best {
		t.Fatal("MarkBest changed the cached list")
	}
}

func TestCapabilitiesNeverNull(t *testing.T) {
	if c := Capabilities("gemma-3n-e4b-it-vision-preview-xyz"); c == nil {
		t.Fatal("caps must be an empty list, not nil, so clients can decode it")
	}
}
