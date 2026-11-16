package store

import (
	"strings"
	"testing"
)

func TestValidateBotFields(t *testing.T) {
	longName := strings.Repeat("n", 41)
	longTitle := strings.Repeat("t", 81)
	longInstr := strings.Repeat("i", 20001)

	name := "  Scout  "
	title := "finds things"
	instr := "be helpful"
	if msg := ValidateBotFields(&name, &title, &instr, nil); msg != "" {
		t.Fatalf("valid fields rejected: %s", msg)
	}
	if name != "Scout" {
		t.Fatalf("name not trimmed: %q", name)
	}

	bad := longName
	if msg := ValidateBotFields(&bad, nil, nil, nil); msg == "" {
		t.Fatal("over-long name accepted")
	}
	empty := "   "
	if msg := ValidateBotFields(&empty, nil, nil, nil); msg == "" {
		t.Fatal("empty name accepted")
	}
	if msg := ValidateBotFields(nil, &longTitle, nil, nil); msg == "" {
		t.Fatal("over-long title accepted")
	}
	if msg := ValidateBotFields(nil, nil, &longInstr, nil); msg == "" {
		t.Fatal("over-long instructions accepted")
	}
}
