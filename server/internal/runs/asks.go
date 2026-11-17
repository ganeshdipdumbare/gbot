package runs

import (
	"strings"
)

// Bots asking bots: the rules shared with the phone. See
// docs/protocol/bot-asks.md; both sides are tested against
// docs/protocol/bot-asks.fixtures.json.

const (
	// maxAskDepth stops a chain: person → A (0) → B (1) → C (2), and C
	// can't message anyone.
	maxAskDepth = 2
	// maxAsksPerRoot bounds the messages one request from the person can
	// set off, wherever they happen in the chain.
	maxAsksPerRoot = 6
)

// canMessage reports whether a bot at depth may send another message,
// given how many this request has already sent.
func canMessage(depth, sent int) bool {
	return depth < maxAskDepth && sent < maxAsksPerRoot
}

// askEnded reports whether an ask has ended, one way or another.
func askEnded(status string) bool {
	switch status {
	case "replied", "delivered", "failed", "needs_you", "expired", "cancelled":
		return true
	}
	return false
}

// roundDone reports whether every ask in a round has ended.
func roundDone(statuses []string) bool {
	for _, s := range statuses {
		if !askEnded(s) {
			return false
		}
	}
	return true
}

// answer is one ask in a round, as the sender gets it back.
type answer struct {
	Name, Message, Status, Reply string
}

// joinPrompt is the one prompt that hands a round's answers back to the
// bot that asked.
func joinPrompt(answers []answer) string {
	var sb strings.Builder
	sb.WriteString("Answers to what you asked your teammates:\n")
	for _, a := range answers {
		msg := clipRunes(strings.Join(strings.Fields(a.Message), " "), 200)
		sb.WriteString("- " + a.Name + ", asked \"" + msg + "\":\n")
		for _, line := range strings.Split(answerText(a), "\n") {
			if strings.TrimSpace(line) == "" {
				sb.WriteString("\n")
			} else {
				sb.WriteString("  " + strings.TrimRight(line, " \t") + "\n")
			}
		}
	}
	sb.WriteString("\nThe person can't read these answers: their card only shows that you messaged the bot. Use them to finish what the person asked, say what matters in your own words now, and don't ask these bots the same thing again.")
	return sb.String()
}

func answerText(a answer) string {
	reply := clipRunes(strings.TrimSpace(a.Reply), 2000)
	switch a.Status {
	case "replied":
		if reply != "" {
			return reply
		}
		return "had nothing to add."
	case "delivered":
		return "had nothing to add."
	case "failed":
		if reply != "" {
			return reply
		}
		return "couldn't answer."
	case "needs_you":
		return "needs the person first; it asked them in its own chat."
	case "cancelled":
		return "was stopped before answering."
	default:
		return "didn't answer in time."
	}
}

// clipRunes shortens s to n runes, marking the cut.
func clipRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return strings.TrimSpace(string(r[:n])) + "…"
}
