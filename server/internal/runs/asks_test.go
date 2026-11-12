package runs

import (
	"encoding/json"
	"os"
	"testing"
)

// The same cases the phone's tests read: one protocol, two runtimes.
type askFixtures struct {
	Limits struct {
		MaxDepth   int `json:"maxDepth"`
		MaxPerRoot int `json:"maxPerRoot"`
	} `json:"limits"`
	CanMessage []struct {
		Depth, Sent int
		OK          bool `json:"ok"`
	} `json:"canMessage"`
	RoundDone []struct {
		Statuses []string `json:"statuses"`
		Done     bool     `json:"done"`
	} `json:"roundDone"`
	JoinPrompt []struct {
		Name    string   `json:"name"`
		Answers []answer `json:"answers"`
		Prompt  string   `json:"prompt"`
	} `json:"joinPrompt"`
}

func loadAskFixtures(t *testing.T) askFixtures {
	t.Helper()
	raw, err := os.ReadFile("../../../docs/protocol/bot-asks.fixtures.json")
	if err != nil {
		t.Fatal(err)
	}
	var f askFixtures
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestAskProtocol(t *testing.T) {
	f := loadAskFixtures(t)
	if f.Limits.MaxDepth != maxAskDepth || f.Limits.MaxPerRoot != maxAsksPerRoot {
		t.Fatalf("limits drifted from the fixtures: %+v", f.Limits)
	}
	for _, c := range f.CanMessage {
		if got := canMessage(c.Depth, c.Sent); got != c.OK {
			t.Errorf("canMessage(%d, %d) = %v, want %v", c.Depth, c.Sent, got, c.OK)
		}
	}
	for _, c := range f.RoundDone {
		if got := roundDone(c.Statuses); got != c.Done {
			t.Errorf("roundDone(%v) = %v, want %v", c.Statuses, got, c.Done)
		}
	}
	for _, c := range f.JoinPrompt {
		if got := joinPrompt(c.Answers); got != c.Prompt {
			t.Errorf("%s:\n got %q\nwant %q", c.Name, got, c.Prompt)
		}
	}
}

// A request's messages are counted wherever they happen in the chain.
func TestAskBudget(t *testing.T) {
	rs := newRounds()
	j := &Job{}
	rs.startRun(j)
	if j.Root == "" {
		t.Fatal("a run from the person starts a request")
	}
	for i := 0; i < maxAsksPerRoot; i++ {
		depth := i % maxAskDepth // asks from the first bot and from the bots it asked
		if !rs.take(j.Root, depth) {
			t.Fatalf("message %d refused", i+1)
		}
	}
	if rs.take(j.Root, 0) {
		t.Fatal("the request went over its budget")
	}
	if rs.take("rt_other", maxAskDepth) {
		t.Fatal("a bot at the deepest level sent a message")
	}
	child := &Job{Root: j.Root, Depth: 1}
	rs.startRun(child)
	if child.Root != j.Root {
		t.Fatal("a bot's run lost its request")
	}
}
