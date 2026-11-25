package runs

import (
	"context"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"gbot/internal/store"
	"github.com/ganeshdipdumbare/kritrim"
)

// What a bot sees of its own conversation, decided before every model
// call. The stored thread keeps everything; the prompt is kept small,
// because every step of a reply sends it again and a bloated prompt is
// what makes a reply slow.
//
//   - A tool's answer from an earlier turn has done its job: the bot's
//     reply already says what mattered. It goes in as a short note with
//     its opening, so a fetched page or a browser read is not resent on
//     every later step. Earlier turns' thinking goes too.
//   - When the thread is still over budget, the oldest turns give way to
//     the saved summary of them. The summary is never written while the
//     person waits: the run uses what is saved and a fresh one is written
//     in the background. Turns the saved summary doesn't cover yet stay
//     in the prompt, so nothing is lost while it catches up.

const (
	// A tool answer from an earlier turn longer than this is shortened.
	staleToolChars = 1200
	// How much of a shortened answer is kept.
	staleToolHead = 400
	// When a single turn alone is over budget, its newest tool answers
	// stay whole and older ones are shortened.
	freshToolResults = 4
	// The summary is rewritten once this many messages have left the
	// prompt since it was last written.
	summaryLag = 8
	// A background summary may take this long before it is given up.
	summaryTimeout = 10 * time.Minute
)

// fitContext trims the request's messages for model m.
func (r *Runtime) fitContext(ctx context.Context, m kritrim.Model, req *kritrim.Request) {
	full := req.Messages
	msgs := lightenEarlier(full)
	budget := r.contextTokens()
	if kritrim.EstimateTokens(msgs) <= budget {
		req.Messages = msgs
		return
	}
	// Room for the summary and the reply's own growth.
	start := windowStart(msgs, budget*3/4)
	if start == 0 {
		req.Messages = lightenTurn(msgs, budget)
		return
	}
	th := threadOf(ctx)
	var old *store.Summary
	if th != "" {
		old, _ = r.store.GetSummary(ctx, th)
	}
	covered := 0
	if old != nil {
		covered = min(old.Covered, len(msgs))
	}
	outside := (old != nil && strings.HasPrefix(old.Summary, outsideMark)) || carriesOutside(full[covered:start])
	if rc := runFrom(ctx); rc != nil && rc.isTainted() {
		outside = true
	}
	if th != "" && (old == nil || start-covered >= summaryLag) {
		r.refreshSummary(ctx, m, th, old, full[:start], outside)
	}

	// Keep what the summary doesn't cover yet, unless that alone is far
	// over budget; then the newest of it goes in roughly.
	cut := userAtOrBefore(msgs, covered)
	text := ""
	if old != nil {
		text = old.Summary
	}
	if cut < start && kritrim.EstimateTokens(msgs[cut:]) > 2*budget {
		rough := markOutside(roughSummary(full[covered:start]), outside)
		if text == "" {
			text = rough
		} else {
			text += "\n\n" + strings.TrimPrefix(rough, outsideMark)
		}
		cut = start
	}
	var out []kritrim.Message
	if cut > 0 && text != "" {
		out = r.summaryFor(ctx, text)
	} else if cut > 0 {
		// Nothing to stand in for the dropped turns: keep them.
		cut = 0
	}
	out = append(out, msgs[cut:]...)
	req.Messages = lightenTurn(out, budget)
}

// refreshSummary rewrites a thread's summary in the background, one at a
// time per thread.
func (r *Runtime) refreshSummary(ctx context.Context, m kritrim.Model, th string, old *store.Summary, dropped []kritrim.Message, outside bool) {
	if _, busy := r.summarizing.LoadOrStore(th, true); busy {
		return
	}
	dropped = append([]kritrim.Message(nil), dropped...)
	bg, cancel := context.WithTimeout(context.WithoutCancel(ctx), summaryTimeout)
	go func() {
		defer cancel()
		defer r.summarizing.Delete(th)
		text, err := summarize(bg, m, old, dropped)
		if err != nil || text == "" {
			if err != nil {
				r.log.Debug("summary", "thread", th, "err", err)
			}
			return
		}
		_ = r.store.SaveSummary(bg, th, len(dropped), markOutside(text, outside))
	}()
}

// lightenEarlier shortens what earlier turns left behind: long tool
// answers, their pictures, and the thinking before them. The current turn,
// from the last message the person sent, is left whole.
func lightenEarlier(msgs []kritrim.Message) []kritrim.Message {
	turn := lastUser(msgs)
	if turn <= 0 {
		return msgs
	}
	names := toolNames(msgs)
	var out []kritrim.Message
	for i, m := range msgs[:turn] {
		short, changed := lighten(m, names)
		if !changed {
			continue
		}
		if out == nil {
			out = append([]kritrim.Message(nil), msgs...)
		}
		out[i] = short
	}
	if out == nil {
		return msgs
	}
	return out
}

// lightenTurn shortens all but the newest few tool answers when the
// prompt is still over budget, as when one turn read many long pages.
func lightenTurn(msgs []kritrim.Message, budget int) []kritrim.Message {
	if kritrim.EstimateTokens(msgs) <= budget {
		return msgs
	}
	names := toolNames(msgs)
	out := append([]kritrim.Message(nil), msgs...)
	fresh := 0
	for i := len(out) - 1; i >= 0; i-- {
		if out[i].Role != kritrim.RoleTool {
			continue
		}
		if fresh < freshToolResults {
			fresh++
			continue
		}
		out[i], _ = lighten(out[i], names)
	}
	return out
}

// lighten returns m with a long tool answer cut to its opening, its
// pictures named instead of sent, and an assistant's thinking dropped.
func lighten(m kritrim.Message, names map[string]string) (kritrim.Message, bool) {
	switch m.Role {
	case kritrim.RoleAssistant:
		if m.Reasoning == "" && m.ReasoningSig == "" {
			return m, false
		}
		m.Reasoning, m.ReasoningSig = "", ""
		return m, true
	case kritrim.RoleTool:
		long := len(m.Content) > staleToolChars
		if !long && len(m.Images) == 0 {
			return m, false
		}
		name := m.ToolName
		if name == "" {
			name = names[m.ToolCallID]
		}
		if name == "" {
			name = "a tool"
		}
		var b strings.Builder
		fmt.Fprintf(&b, "[Earlier answer from %s", name)
		if long {
			fmt.Fprintf(&b, ", %d characters, shortened. It began:\n%s…\n", len(m.Content), headOf(m.Content, staleToolHead))
		} else {
			b.WriteString(":\n" + m.Content + "\n")
		}
		if n := len(m.Images); n > 0 {
			fmt.Fprintf(&b, "It also held %d picture(s), not shown again.\n", n)
		}
		b.WriteString("Call the tool again if you need the rest.]")
		m.Content = b.String()
		m.Images = nil
		return m, true
	}
	return m, false
}

// windowStart is where a prompt of at most keep tokens begins: always at a
// message from the person, so no tool answer loses its call. It is never
// later than the start of the current turn, and 0 when everything fits.
func windowStart(msgs []kritrim.Message, keep int) int {
	start, n := len(msgs), 0
	for start > 0 {
		n += kritrim.EstimateTokens(msgs[start-1 : start])
		if n > keep {
			break
		}
		start--
	}
	for start < len(msgs) && msgs[start].Role != kritrim.RoleUser {
		start++
	}
	if turn := lastUser(msgs); turn >= 0 && start > turn {
		start = turn
	}
	return max(start, 0)
}

// userAtOrBefore is the last message from the person at or before i, or 0.
func userAtOrBefore(msgs []kritrim.Message, i int) int {
	for i = min(i, len(msgs)-1); i > 0; i-- {
		if msgs[i].Role == kritrim.RoleUser {
			return i
		}
	}
	return 0
}

func lastUser(msgs []kritrim.Message) int {
	for i := len(msgs) - 1; i >= 0; i-- {
		if msgs[i].Role == kritrim.RoleUser {
			return i
		}
	}
	return -1
}

func toolNames(msgs []kritrim.Message) map[string]string {
	names := map[string]string{}
	for _, m := range msgs {
		for _, c := range m.ToolCalls {
			names[c.ID] = c.Name
		}
	}
	return names
}

// headOf is the first n bytes of s, not splitting a character.
func headOf(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}
