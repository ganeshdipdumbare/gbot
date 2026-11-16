package runs

import (
	"context"
	"strings"
	"testing"

	"github.com/ganeshdipdumbare/kritrim"

	"gbot/internal/store"
)

// The roster tools belong to the person's main bot, the same four phone
// mode offers it. Any other bot must not be able to hatch, change or
// archive a teammate.
func TestRosterToolsAreTheMainBots(t *testing.T) {
	r := &Runtime{}
	names := func(b *store.Bot, g *store.Group, forUser string) map[string]kritrim.Tool {
		out := map[string]kritrim.Tool{}
		for _, tl := range r.runTools(context.Background(), &Job{Bot: b, Group: g, For: forUser}) {
			out[tl.Name] = tl
		}
		return out
	}
	roster := []string{"bot_list", "bot_create", "bot_update", "bot_archive"}
	owner := "00000000-0000-4000-8000-000000000002"
	otherUser := "00000000-0000-4000-8000-000000000099"
	main := &store.Bot{ID: "00000000-0000-4000-8000-000000000001", OwnerID: owner, Main: true}
	other := &store.Bot{ID: "00000000-0000-4000-8000-000000000003", OwnerID: owner}

	got := names(main, nil, owner)
	for _, n := range roster {
		if _, ok := got[n]; !ok {
			t.Fatalf("%s not offered to the main bot", n)
		}
	}
	for _, n := range roster {
		if _, ok := names(other, nil, owner)[n]; ok {
			t.Fatalf("%s must not be offered to an ordinary bot", n)
		}
	}
	// A group chat is a conversation, not the place to change the roster.
	for _, n := range roster {
		if _, ok := names(main, &store.Group{ID: "g1", UserID: owner}, owner)[n]; ok {
			t.Fatalf("%s must not be offered in a group", n)
		}
	}
	// A shared main bot must not hand the roster tools to every member.
	for _, n := range roster {
		if _, ok := names(main, nil, otherUser)[n]; ok {
			t.Fatalf("%s must not be offered to a non-owner chatting with the main bot", n)
		}
	}
	// Reading the roster is free; every change asks the person first.
	if !readonlyTool("bot_list") {
		t.Error("bot_list only reads and should not need approval")
	}
	for _, n := range []string{"bot_create", "bot_update", "bot_archive"} {
		if readonlyTool(n) {
			t.Errorf("%s changes the roster and must need approval", n)
		}
		// Auto mode runs most things unattended, but the person's own
		// team is theirs to decide on.
		if !alwaysAsk[n] {
			t.Errorf("%s must ask in every mode that asks at all", n)
		}
		if d := describeCall(n, map[string]any{"name": "Scout", "bot": "Scout"}); !strings.Contains(d, "Scout") {
			t.Errorf("%s has no card of its own: %q", n, d)
		}
		if l := toolLabel(n); strings.HasPrefix(l, "Using ") {
			t.Errorf("%s has no label of its own: %q", n, l)
		}
	}
}

// Roster changes must match the HTTP API: managers can edit, only the
// owner can archive. Collaborators who can merely see a bot cannot.
func TestRosterRoleGates(t *testing.T) {
	cases := []struct {
		role                  string
		canManage, canArchive bool
	}{
		{"owner", true, true},
		{"manager", true, false},
		{"collaborator", false, false},
		{"", false, false},
	}
	for _, c := range cases {
		b := &store.Bot{Role: c.role}
		if got := rosterCanManage(b); got != c.canManage {
			t.Errorf("role %q manage: got %v want %v", c.role, got, c.canManage)
		}
		if got := rosterCanArchive(b); got != c.canArchive {
			t.Errorf("role %q archive: got %v want %v", c.role, got, c.canArchive)
		}
	}
}
