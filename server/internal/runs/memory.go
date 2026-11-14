package runs

import (
	"context"
	"fmt"
	"strings"

	"github.com/ganeshdipdumbare/kritrim"
)

// Long chats outgrow the model's window. kritrim drops the oldest turns;
// this keeps a rolling summary of what it dropped so the bot still knows
// what happened, without paying to summarize the same prefix every turn.

type threadKey struct{}

func withThread(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, threadKey{}, id)
}

func threadOf(ctx context.Context) string {
	s, _ := ctx.Value(threadKey{}).(string)
	return s
}

func (r *Runtime) contextTokens() int {
	if r.cfg.ContextTokens > 0 {
		return r.cfg.ContextTokens
	}
	return 24_000
}

func (r *Runtime) summarizer(m kritrim.Model) func(context.Context, []kritrim.Message) ([]kritrim.Message, error) {
	return func(ctx context.Context, dropped []kritrim.Message) ([]kritrim.Message, error) {
		th := threadOf(ctx)
		if th == "" {
			return summaryMessages(roughSummary(dropped)), nil
		}
		old, _ := r.store.GetSummary(ctx, th)
		if old != nil && old.Covered >= len(dropped) {
			return summaryMessages(old.Summary), nil
		}
		start := 0
		if old != nil {
			start = min(old.Covered, len(dropped))
		}
		var b strings.Builder
		if old != nil && old.Summary != "" {
			b.WriteString("Previous summary:\n" + old.Summary + "\n\n")
		}
		b.WriteString("New earlier messages:\n")
		for _, msg := range dropped[start:] {
			text := strings.TrimSpace(msg.Content)
			if text == "" {
				continue
			}
			if len(text) > 2000 {
				text = text[:2000] + "…"
			}
			who := "Bot"
			switch msg.Role {
			case kritrim.RoleUser:
				who = "Person"
			case kritrim.RoleTool:
				who = "Tool"
			}
			fmt.Fprintf(&b, "%s: %s\n", who, text)
		}
		prompt := `You keep a running summary of a long conversation between a person and an AI teammate.
Update the summary with the new earlier messages below. Keep durable facts, names, numbers, links,
preferences, decisions and unfinished work. Drop pleasantries and tool noise. Write at most 400 words.
Reply with the summary only, no preamble.

` + b.String()
		res, err := m.Generate(ctx, kritrim.Request{
			Messages:  []kritrim.Message{{Role: kritrim.RoleUser, Content: prompt}},
			MaxTokens: 900,
		})
		if err != nil || res == nil || strings.TrimSpace(res.Text) == "" {
			if old != nil {
				return summaryMessages(old.Summary), nil
			}
			return summaryMessages(roughSummary(dropped)), nil
		}
		text := strings.TrimSpace(res.Text)
		_ = r.store.SaveSummary(ctx, th, len(dropped), text)
		return summaryMessages(text), nil
	}
}

func summaryMessages(text string) []kritrim.Message {
	return []kritrim.Message{{
		Role:    kritrim.RoleUser,
		Content: "[Earlier in this conversation, summarized]\n" + text,
	}}
}

// roughSummary is the fallback when the model cannot summarize: keep the
// newest few dropped turns so the run still has some continuity.
func roughSummary(dropped []kritrim.Message) string {
	start := max(0, len(dropped)-6)
	var b strings.Builder
	for _, msg := range dropped[start:] {
		text := strings.TrimSpace(msg.Content)
		if text == "" {
			continue
		}
		if len(text) > 500 {
			text = text[:500] + "…"
		}
		who := "Bot"
		if msg.Role == kritrim.RoleUser {
			who = "Person"
		} else if msg.Role == kritrim.RoleTool {
			who = "Tool"
		}
		fmt.Fprintf(&b, "- %s: %s\n", who, text)
	}
	if b.Len() == 0 {
		return "(earlier turns are no longer in context)"
	}
	return "The model could not summarize these earlier turns; the newest ones were:\n" + b.String()
}
