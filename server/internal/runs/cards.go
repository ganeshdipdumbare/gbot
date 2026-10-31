package runs

import (
	"context"
	"errors"
	"fmt"

	"github.com/ganeshdipdumbare/eipagent"
	"github.com/ganeshdipdumbare/eipagent/sdk"

	"gbot/internal/store"
)

// A card is a paused tool call shown in the chat: an approval, a
// question, or a request to take over the computer. Answering every card
// of a run resumes it.

func (r *Runtime) addCard(ctx context.Context, c convo, b *store.Bot, runID string, in sdk.Interrupt) {
	data := map[string]any{"kind": in.Kind, "interruptId": in.ID, "status": "pending", "botId": b.ID}
	if in.ToolCall != nil {
		data["tool"] = in.ToolCall.Name
		if in.Kind == "approval" {
			data["args"] = in.ToolCall.Args
		}
	}
	if opts, ok := in.Payload["options"].([]any); ok && len(opts) > 0 {
		data["options"] = opts
	}
	m := &store.Message{BotID: b.ID, GroupID: c.GroupID, Role: "card", Content: in.Message, Data: data, RunID: &runID}
	if err := r.store.AddMessage(ctx, m, nil); err != nil {
		r.log.Error("add card", "err", err)
		return
	}
	r.hub.Publish(c.SpaceID, Event{Type: "message", BotID: b.ID, GroupID: c.GroupID, Data: m})
}

var ErrCardDone = errors.New("this card was already answered")

// Answer records the person's decision on a card. decision is approve,
// reject or answer; always=true also allows the tool from now on.
func (r *Runtime) Answer(ctx context.Context, owner *store.User, card *store.Message, bot *store.Bot, group *store.Group, decision, text string, always bool) (*store.Message, error) {
	if card.Role != "card" || card.Data["status"] != "pending" || card.RunID == nil {
		return nil, ErrCardDone
	}
	id, _ := card.Data["interruptId"].(string)
	kind, _ := card.Data["kind"].(string)
	var d sdk.Decision
	status := ""
	switch {
	case kind == "approval" && decision == "approve":
		d, status = eipagent.Approve(id), "approved"
		if tool, _ := card.Data["tool"].(string); always && tool != "" {
			_ = r.store.SetToolRule(ctx, owner.ID, tool, "allow")
		}
	case kind == "approval" && decision == "reject":
		msg := "The person declined."
		if text != "" {
			msg += " They said: " + text
		}
		d, status = eipagent.Reject(id, msg), "denied"
	case kind == "question" && decision == "answer" && text != "":
		d, status = eipagent.Respond(id, text), "answered"
	case kind == "takeover" && decision == "answer":
		if text == "" {
			text = "I'm done. The computer is yours again."
		}
		d, status = eipagent.Respond(id, text), "done"
		if r.computer != nil {
			_ = r.computer.TakeControl(bot.ID, false)
		}
	default:
		return nil, fmt.Errorf("can't %s a %s card", decision, kind)
	}
	d.By = owner.ID
	upd, err := r.store.MergeMessageData(ctx, card.ID, map[string]any{"status": status, "answer": text,
		"decision": map[string]any{"interruptId": d.InterruptID, "type": d.Type, "message": d.Message}})
	if err != nil {
		return nil, err
	}
	r.hub.Publish(bot.SpaceID, Event{Type: "message_update", BotID: card.BotID, GroupID: card.GroupID, Data: upd})

	// Resume once every card from that run has an answer.
	pending, err := r.store.PendingCards(ctx, card.BotID, card.GroupID)
	if err != nil {
		return upd, err
	}
	for _, p := range pending {
		if p.RunID != nil && *p.RunID == *card.RunID {
			return upd, nil
		}
	}
	decisions := r.runDecisions(ctx, card)
	r.Enqueue(Job{Bot: bot, Group: group, Trigger: "resume", Decisions: decisions, ResumeRun: *card.RunID})
	return upd, nil
}

// runDecisions collects the recorded answers of every card in a run.
func (r *Runtime) runDecisions(ctx context.Context, card *store.Message) []sdk.Decision {
	var msgs []*store.Message
	if card.GroupID != "" {
		msgs, _ = r.store.ListGroupMessages(ctx, card.GroupID, nil, 200)
	} else {
		msgs, _ = r.store.ListMessages(ctx, card.BotID, nil, 200)
	}
	var out []sdk.Decision
	for _, m := range msgs {
		if m.Role != "card" || m.RunID == nil || *m.RunID != *card.RunID {
			continue
		}
		raw, _ := m.Data["decision"].(map[string]any)
		if raw == nil {
			continue
		}
		d := sdk.Decision{}
		d.InterruptID, _ = raw["interruptId"].(string)
		d.Type, _ = raw["type"].(string)
		d.Message, _ = raw["message"].(string)
		out = append(out, d)
	}
	return out
}

// skipPending answers a thread's open cards with the person's new
// message, so the chat moves on instead of waiting.
func (r *Runtime) skipPending(ctx context.Context, c convo, pending []sdk.Interrupt, prompt string) []sdk.Decision {
	cards, _ := r.store.PendingCards(ctx, c.BotID, c.GroupID)
	for _, card := range cards {
		if upd, err := r.store.MergeMessageData(ctx, card.ID, map[string]any{"status": "skipped"}); err == nil {
			r.hub.Publish(c.SpaceID, Event{Type: "message_update", BotID: card.BotID, GroupID: card.GroupID, Data: upd})
		}
	}
	out := make([]sdk.Decision, 0, len(pending))
	for _, in := range pending {
		if in.Kind == "approval" {
			out = append(out, eipagent.Reject(in.ID, "Not approved. The person wrote instead: "+prompt))
		} else {
			out = append(out, eipagent.Respond(in.ID, "The person didn't answer that and wrote instead: "+prompt))
		}
	}
	return out
}

// expireCards closes a group's open cards when the chat moves on.
func (r *Runtime) expireCards(ctx context.Context, c convo) {
	cards, _ := r.store.PendingCards(ctx, c.BotID, c.GroupID)
	for _, card := range cards {
		if upd, err := r.store.MergeMessageData(ctx, card.ID, map[string]any{"status": "skipped"}); err == nil {
			r.hub.Publish(c.SpaceID, Event{Type: "message_update", BotID: card.BotID, GroupID: card.GroupID, Data: upd})
		}
		if card.RunID != nil {
			r.mu.Lock()
			delete(r.groupPending, *card.RunID)
			r.mu.Unlock()
		}
	}
}
