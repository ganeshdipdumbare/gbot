package api_test

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"gbot/internal/api"
	"gbot/internal/models"
	"gbot/internal/routines"
	"gbot/internal/runs"
	"gbot/internal/store"
)

// Sharing: a team's members use only the bots their owner shares, see only
// their own chats, answer only their own cards, and never see another
// person's events.
func TestSharing(t *testing.T) {
	dsn := testDB(t)
	ctx := context.Background()
	st, err := store.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	cat := models.NewCatalog("http://127.0.0.1:1")
	cat.Extra = []models.Model{
		{ID: "mock:test", Label: "mock", Provider: "mock", Tools: true},
		{ID: "mockscript:x", Provider: "mockscript", Tools: true},
	}
	hub := runs.NewHub()
	// Only the card bot uses the script: first a routine to approve, then a reply.
	script := []any{
		map[string]any{"tool": "routine_create", "args": map[string]any{"name": "Daily", "prompt": "Say hi", "schedule": "0 9 * * *"}},
		map[string]any{"text": "Routine is set."},
	}
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, Mock: true, MockScript: script}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	rs := &routines.Service{Store: st, Log: log}
	rt.Use(nil, rs)
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, Routines: rs, AllowSignup: true}).Handler())
	defer srv.Close()

	signup := func(email string) (*client, store.User) {
		c := newClient(t, srv.URL)
		var u store.User
		if code := c.do("POST", "/api/auth/signup", map[string]string{"email": email, "password": "long-enough-pw"}, &u); code != 200 {
			t.Fatalf("signup %s: %d", email, code)
		}
		return c, u
	}
	alice, aliceU := signup("alice@example.com")
	bob, bobU := signup("bob@example.com")

	var team store.Space
	if code := alice.do("POST", "/api/spaces", map[string]string{"name": "Gbot team"}, &team); code != 201 {
		t.Fatalf("create team: %d", code)
	}
	if code := alice.do("PUT", "/api/me/space", map[string]string{"spaceId": team.ID}, nil); code != 200 {
		t.Fatalf("alice switches to team: %d", code)
	}
	var pip store.Bot
	if code := alice.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mock:test"}, &pip); code != 201 {
		t.Fatalf("create Pip: %d", code)
	}
	var cardy store.Bot
	if code := alice.do("POST", "/api/bots", map[string]any{"name": "Cardy", "model": "mockscript:x"}, &cardy); code != 201 {
		t.Fatalf("create Cardy: %d", code)
	}

	// Bob isn't on the team yet, so he can't see Pip at all.
	if code := bob.do("GET", "/api/bots/"+pip.ID, nil, nil); code != 404 {
		t.Fatalf("non-member saw a private bot: %d", code)
	}
	var sharing struct{ Members []store.Member }
	if code := alice.do("POST", "/api/bots/"+pip.ID+"/sharing/members", map[string]string{"email": "bob@example.com", "role": "collaborator"}, nil); code != 400 {
		t.Fatalf("added a non-member to a bot: %d", code)
	}
	if code := alice.do("POST", "/api/spaces/"+team.ID+"/members", map[string]string{"email": "bob@example.com"}, nil); code != 200 {
		t.Fatalf("add bob to team: %d", code)
	}
	if code := bob.do("PUT", "/api/me/space", map[string]string{"spaceId": team.ID}, nil); code != 200 {
		t.Fatalf("bob switches to team: %d", code)
	}

	t.Run("a private bot stays with its owner", func(t *testing.T) {
		if code := bob.do("GET", "/api/bots/"+pip.ID, nil, nil); code != 404 {
			t.Fatalf("teammate saw a private bot: %d", code)
		}
		var res struct{ Bots []store.Bot }
		bob.do("GET", "/api/bots", nil, &res)
		for _, b := range res.Bots {
			if b.ID == pip.ID {
				t.Fatal("private bot in a teammate's roster")
			}
		}
	})

	t.Run("sharing with the team makes it usable, as a collaborator", func(t *testing.T) {
		if code := alice.do("PUT", "/api/bots/"+pip.ID+"/sharing", map[string]string{"visibility": "team"}, nil); code != 200 {
			t.Fatalf("share: %d", code)
		}
		var b store.Bot
		if code := bob.do("GET", "/api/bots/"+pip.ID, nil, &b); code != 200 || b.Role != "collaborator" {
			t.Fatalf("bob sees Pip as %d / %q, want 200 collaborator", code, b.Role)
		}
		if code := bob.do("PATCH", "/api/bots/"+pip.ID, map[string]string{"name": "Hacked"}, nil); code != 403 {
			t.Fatalf("collaborator edited the bot: %d", code)
		}
		if code := bob.do("DELETE", "/api/bots/"+pip.ID, nil, nil); code != 403 {
			t.Fatalf("collaborator deleted the bot: %d", code)
		}
	})

	t.Run("each person's chat is private", func(t *testing.T) {
		alice.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "secret from alice"}, nil)
		bob.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "hi from bob"}, nil)
		waitForBotReply(t, bob, pip.ID)
		waitForBotReply(t, alice, pip.ID)

		var aliceChat, bobChat struct{ Messages []store.Message }
		alice.do("GET", "/api/bots/"+pip.ID+"/messages", nil, &aliceChat)
		bob.do("GET", "/api/bots/"+pip.ID+"/messages", nil, &bobChat)
		if chatHas(aliceChat.Messages, "hi from bob") {
			t.Fatal("alice can read bob's chat")
		}
		if chatHas(bobChat.Messages, "secret from alice") {
			t.Fatal("bob can read alice's chat")
		}
		if !chatHas(bobChat.Messages, "hi from bob") || !chatHas(aliceChat.Messages, "secret from alice") {
			t.Fatal("people lost their own messages")
		}
	})

	t.Run("live events reach only the person they're for", func(t *testing.T) {
		var res struct {
			Events []json.RawMessage `json:"events"`
		}
		bob.do("GET", "/api/events/poll?after=1", nil, &res)
		if len(res.Events) == 0 {
			t.Fatal("bob got no events")
		}
		for _, e := range res.Events {
			if strings.Contains(string(e), "secret from alice") {
				t.Fatalf("bob received alice's message: %s", e)
			}
		}
	})

	t.Run("a shared bot's approvals are the requester's", func(t *testing.T) {
		// Bob asks to approve tools; Alice doesn't (her mode is the default).
		if code := bob.do("PATCH", "/api/me", map[string]string{"approvalMode": "ask"}, nil); code != 200 {
			t.Fatalf("set approval mode: %d", code)
		}
		if code := alice.do("PUT", "/api/bots/"+cardy.ID+"/sharing", map[string]string{"visibility": "team"}, nil); code != 200 {
			t.Fatalf("share Cardy: %d", code)
		}
		bob.do("POST", "/api/bots/"+cardy.ID+"/messages", map[string]string{"text": "schedule a daily hi"}, nil)
		card := waitForPendingCard(t, bob, cardy.ID)
		// Alice can't see or answer a card in Bob's chat.
		var aliceChat struct{ Messages []store.Message }
		alice.do("GET", "/api/bots/"+cardy.ID+"/messages", nil, &aliceChat)
		for _, m := range aliceChat.Messages {
			if m.Role == "card" {
				t.Fatal("alice sees bob's approval card")
			}
		}
		if code := alice.do("POST", "/api/cards/"+card.ID, map[string]any{"decision": "approve"}, nil); code != 404 {
			t.Fatalf("alice answered bob's card: %d", code)
		}
		// Bob approves with Always allow: the rule is his alone.
		if code := bob.do("POST", "/api/cards/"+card.ID, map[string]any{"decision": "approve", "always": true}, nil); code != 200 {
			t.Fatalf("bob approves: %d", code)
		}
		waitForBotReply(t, bob, cardy.ID)
		var bobRules, aliceRules struct{ Rules []store.ToolRule }
		bob.do("GET", "/api/settings/rules", nil, &bobRules)
		alice.do("GET", "/api/settings/rules", nil, &aliceRules)
		// The card's tool is high-risk, so "always allow" is refused for
		// everyone: no rule for Bob, and nothing leaks to Alice.
		if len(bobRules.Rules) != 0 {
			t.Fatalf("a blanket rule was saved for a high-risk tool: %+v", bobRules)
		}
		if len(aliceRules.Rules) != 0 {
			t.Fatalf("bob's always-allow leaked to alice: %+v", aliceRules)
		}
	})

	t.Run("a group is its maker's", func(t *testing.T) {
		var g store.Group
		if code := bob.do("POST", "/api/groups", map[string]any{"name": "Crew", "botIds": []string{pip.ID, cardy.ID}}, &g); code != 201 {
			t.Fatalf("create group: %d", code)
		}
		var list struct{ Groups []store.Group }
		alice.do("GET", "/api/groups", nil, &list)
		for _, x := range list.Groups {
			if x.ID == g.ID {
				t.Fatal("alice lists bob's group")
			}
		}
		if code := alice.do("GET", "/api/groups/"+g.ID+"/messages", nil, nil); code != 404 {
			t.Fatalf("alice opened bob's group: %d", code)
		}
	})

	t.Run("a manager can change the bot, but only the owner can delete it", func(t *testing.T) {
		if code := alice.do("POST", "/api/bots/"+pip.ID+"/sharing/members", map[string]string{"email": "bob@example.com", "role": "manager"}, &sharing); code != 200 {
			t.Fatalf("make bob a manager: %d", code)
		}
		if code := bob.do("PATCH", "/api/bots/"+pip.ID, map[string]string{"name": "Pip II"}, nil); code != 200 {
			t.Fatalf("manager couldn't edit: %d", code)
		}
		if code := bob.do("DELETE", "/api/bots/"+pip.ID, nil, nil); code != 403 {
			t.Fatalf("manager deleted the bot: %d", code)
		}
	})

	t.Run("a manager can't demote or promote managers", func(t *testing.T) {
		// Bob is a manager; he tries to make a teammate a manager, and to
		// demote himself. Only the owner can change manager roles.
		signup("carol@example.com")
		if code := alice.do("POST", "/api/spaces/"+team.ID+"/members", map[string]string{"email": "carol@example.com"}, nil); code != 200 {
			t.Fatalf("add carol: %d", code)
		}
		if code := bob.do("POST", "/api/bots/"+pip.ID+"/sharing/members", map[string]string{"email": "carol@example.com", "role": "manager"}, nil); code != 403 {
			t.Fatalf("manager promoted someone: %d", code)
		}
		if code := bob.do("POST", "/api/bots/"+pip.ID+"/sharing/members", map[string]string{"email": "bob@example.com", "role": "collaborator"}, nil); code != 403 {
			t.Fatalf("manager demoted a manager: %d", code)
		}
		if code := alice.do("POST", "/api/bots/"+pip.ID+"/sharing/members", map[string]string{"email": "carol@example.com", "role": "collaborator"}, nil); code != 200 {
			t.Fatalf("owner added carol: %d", code)
		}
	})

	t.Run("taking a bot private hides it from the team", func(t *testing.T) {
		if code := alice.do("PUT", "/api/bots/"+pip.ID+"/sharing", map[string]string{"visibility": "private"}, nil); code != 200 {
			t.Fatalf("make private: %d", code)
		}
		if code := bob.do("GET", "/api/bots/"+pip.ID, nil, nil); code != 404 {
			t.Fatalf("bob still reaches a private bot: %d", code)
		}
		if code := bob.do("GET", "/api/bots/"+pip.ID+"/messages", nil, nil); code != 404 {
			t.Fatalf("bob still reads a private bot's chat: %d", code)
		}
	})

	t.Run("removing someone from the team ends their access", func(t *testing.T) {
		if code := alice.do("PUT", "/api/bots/"+pip.ID+"/sharing", map[string]string{"visibility": "team"}, nil); code != 200 {
			t.Fatalf("share again: %d", code)
		}
		if code := alice.do("DELETE", "/api/spaces/"+team.ID+"/members/"+bobU.ID, nil, nil); code != 200 {
			t.Fatalf("remove bob: %d", code)
		}
		if code := bob.do("GET", "/api/bots/"+pip.ID, nil, nil); code != 404 {
			t.Fatalf("removed member still reaches the team's bot: %d", code)
		}
		var me store.User
		bob.do("GET", "/api/me", nil, &me)
		if me.SpaceID == team.ID {
			t.Fatal("removed member still works in the team")
		}
	})

	t.Run("owners can't be removed from their team", func(t *testing.T) {
		if code := alice.do("DELETE", "/api/spaces/"+team.ID+"/members/"+aliceU.ID, nil, nil); code != 404 {
			t.Fatalf("removed the owner: %d", code)
		}
	})
}

func chatHas(ms []store.Message, text string) bool {
	for _, m := range ms {
		if strings.Contains(m.Content, text) {
			return true
		}
	}
	return false
}

// waitForBotReply waits until the person's chat with the bot ends with a
// reply from the bot.
func waitForBotReply(t *testing.T, c *client, botID string) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+botID+"/messages", nil, &res)
		if n := len(res.Messages); n > 0 && res.Messages[n-1].Role == "bot" {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("timed out waiting for a reply")
}

func waitForPendingCard(t *testing.T, c *client, botID string) store.Message {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+botID+"/messages", nil, &res)
		for _, m := range res.Messages {
			if m.Role == "card" && m.Data["status"] == "pending" {
				return m
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("timed out waiting for an approval card")
	return store.Message{}
}
