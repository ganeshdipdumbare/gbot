package runs

import (
	"strings"
	"testing"

	"github.com/ganeshdipdumbare/kritrim"
)

func TestRoughSummaryKeepsNewestTurns(t *testing.T) {
	dropped := []kritrim.Message{{Role: kritrim.RoleUser, Content: "old fact"}}
	for i := 0; i < 7; i++ {
		dropped = append(dropped, kritrim.Message{Role: kritrim.RoleAssistant, Content: "older answer"})
	}
	dropped = append(dropped, kritrim.Message{Role: kritrim.RoleUser, Content: "the deadline is Friday"})
	got := roughSummary(dropped)
	if !strings.Contains(got, "deadline is Friday") || strings.Contains(got, "old fact") {
		t.Fatalf("rough summary kept the wrong turns:\n%s", got)
	}
	if !strings.Contains(got, "Person:") || !strings.Contains(got, "Bot:") {
		t.Fatalf("rough summary lost who spoke:\n%s", got)
	}
}

func TestSummaryMessagesAreSyntheticUserText(t *testing.T) {
	msgs := summaryMessages("they chose the blue theme")
	if len(msgs) != 1 || msgs[0].Role != kritrim.RoleUser || !strings.Contains(msgs[0].Content, "blue theme") {
		t.Fatalf("summary message: %+v", msgs)
	}
}

func TestQueueable(t *testing.T) {
	for _, trigger := range []string{"chat", "routine", "webhook", "resume"} {
		if !queueable(trigger) {
			t.Fatalf("%s should survive a restart", trigger)
		}
	}
	for _, trigger := range []string{"handoff", "bot"} {
		if queueable(trigger) {
			t.Fatalf("%s is derived work and should not be queued", trigger)
		}
	}
}
