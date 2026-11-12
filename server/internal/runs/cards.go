package runs

import (
	"context"
	"errors"
	"fmt"

	"github.com/ganeshdipdumbare/kritrim"

	"gbot/internal/computer"
	"gbot/internal/store"
)

// A card is a paused tool call shown in the chat: an approval, a
// question, or a request to take over the computer. Answering every card
// of a run resumes it.
//
// Only the person a card belongs to can answer it: the owner of their
// private chat with the bot, or the creator of a group.

func (r *Runtime) addCard(ctx context.Context, c convo, b *store.Bot, runID, thread string, in kritrim.Interrupt, kind string) {
	data := map[string]any{"kind": kind, "interruptId": in.ID, "status": "pending", "botId": b.ID, "tool": in.Tool}
	if c.GroupID != "" {
		// A group's paused session lives under its own thread; a second
		// pause on the same chain must find it again.
		data["thread"] = thread
	}
	msg := in.Message
	switch kind {
	case "approval":
		data["args"] = in.Args
		msg = describeCall(in.Tool, in.Args)
	case "question":
		msg, _ = in.Args["question"].(string)
		if opts, ok := in.Args["options"].([]any); ok && len(opts) > 0 {
			data["options"] = opts
		}
	case "takeover":
		msg, _ = in.Args["reason"].(string)
	}
	m := &store.Message{BotID: b.ID, GroupID: c.GroupID, ChatUser: c.User, Role: "card", Content: msg, Data: data, RunID: &runID}
	if err := r.store.AddMessage(ctx, m, nil); err != nil {
		r.log.Error("add card", "err", err)
		return
	}
	r.chat(c, Event{Type: "message", BotID: b.ID, GroupID: c.GroupID, Data: m})
}

var (
	ErrCardDone     = errors.New("this card was already answered")
	ErrNotRequester = errors.New("only the person this chat is with can answer it")
)

// cardConvo is the chat a card sits in.
func cardConvo(card *store.Message, bot *store.Bot, group *store.Group) convo {
	if group != nil {
		return newConvo(bot.SpaceID, "", group.ID, group.UserID)
	}
	return newConvo(bot.SpaceID, bot.ID, "", card.ChatUser)
}

// Answer records the person's decision on a card. decision is approve,
// reject or answer; always=true also allows the tool for that person from
// now on.
func (r *Runtime) Answer(ctx context.Context, caller *store.User, card *store.Message, bot *store.Bot, group *store.Group, decision, text string, always bool) (*store.Message, error) {
	if card.Role != "card" || card.Data["status"] != "pending" || card.RunID == nil {
		return nil, ErrCardDone
	}
	c := cardConvo(card, bot, group)
	if c.User != caller.ID {
		return nil, ErrNotRequester
	}
	id, _ := card.Data["interruptId"].(string)
	kind, _ := card.Data["kind"].(string)
	var typ, message, status string
	switch {
	case kind == "approval" && decision == "approve":
		typ, status = "approve", "approved"
		if tool, _ := card.Data["tool"].(string); always && tool != "" {
			_ = r.store.SetToolRule(ctx, caller.ID, tool, "allow")
		}
	case kind == "approval" && decision == "reject":
		message = "The person declined."
		if text != "" {
			message += " They said: " + text
		}
		typ, status = "reject", "denied"
	case kind == "question" && decision == "answer" && text != "":
		typ, message, status = "respond", text, "answered"
	case kind == "takeover" && decision == "answer":
		if text == "" {
			text = "I'm done. The computer is yours again."
		}
		typ, message, status = "respond", text, "done"
		if r.computer != nil {
			_ = r.computer.TakeControl(computer.Owner{Bot: bot.ID, User: caller.ID, Hosts: bot.AllowedHosts}, false)
		}
	default:
		return nil, fmt.Errorf("can't %s a %s card", decision, kind)
	}
	upd, err := r.store.MergeMessageData(ctx, card.ID, map[string]any{"status": status, "answer": text,
		"decision": map[string]any{"interruptId": id, "type": typ, "message": message}})
	if err != nil {
		return nil, err
	}
	r.chat(c, Event{Type: "message_update", BotID: card.BotID, GroupID: card.GroupID, Data: upd})

	// Resume once every card from that run has an answer.
	pending, err := r.store.PendingCards(ctx, card.BotID, c.User, card.GroupID)
	if err != nil {
		return upd, err
	}
	for _, p := range pending {
		if p.RunID != nil && *p.RunID == *card.RunID {
			return upd, nil
		}
	}
	decisions := r.runDecisions(ctx, card, c)
	thread, _ := card.Data["thread"].(string)
	r.Enqueue(Job{Bot: bot, Group: group, For: c.User, Trigger: "resume", Decisions: decisions, Thread: thread})
	return upd, nil
}

// runDecisions collects the recorded answers of every card in a run.
func (r *Runtime) runDecisions(ctx context.Context, card *store.Message, c convo) map[string]kritrim.Decision {
	var msgs []*store.Message
	if c.GroupID != "" {
		msgs, _ = r.store.ListGroupMessages(ctx, c.GroupID, nil, 200)
	} else {
		msgs, _ = r.store.ListMessages(ctx, c.BotID, c.User, nil, 200)
	}
	out := map[string]kritrim.Decision{}
	for _, m := range msgs {
		if m.Role != "card" || m.RunID == nil || *m.RunID != *card.RunID {
			continue
		}
		raw, _ := m.Data["decision"].(map[string]any)
		if raw == nil {
			continue
		}
		id, _ := raw["interruptId"].(string)
		typ, _ := raw["type"].(string)
		message, _ := raw["message"].(string)
		switch typ {
		case "approve":
			out[id] = kritrim.Approve()
		case "reject":
			out[id] = kritrim.Reject(message)
		case "respond":
			out[id] = kritrim.Respond(message)
		}
	}
	return out
}

// skipPending answers a thread's open cards with the person's new
// message, so the chat moves on instead of waiting.
func (r *Runtime) skipPending(ctx context.Context, c convo, pending []kritrim.Interrupt, prompt string) map[string]kritrim.Decision {
	cards, _ := r.store.PendingCards(ctx, c.BotID, c.User, c.GroupID)
	for _, card := range cards {
		if upd, err := r.store.MergeMessageData(ctx, card.ID, map[string]any{"status": "skipped"}); err == nil {
			r.chat(c, Event{Type: "message_update", BotID: card.BotID, GroupID: card.GroupID, Data: upd})
		}
	}
	out := map[string]kritrim.Decision{}
	for _, in := range pending {
		if in.Tool == "ask_user" || in.Tool == "request_takeover" {
			out[in.ID] = kritrim.Respond("The person didn't answer that and wrote instead: " + prompt)
		} else {
			out[in.ID] = kritrim.Reject("Not approved. The person wrote instead: " + prompt)
		}
	}
	return out
}

// expireCards closes a group's open cards when the chat moves on.
func (r *Runtime) expireCards(ctx context.Context, c convo) {
	cards, _ := r.store.PendingCards(ctx, c.BotID, c.User, c.GroupID)
	for _, card := range cards {
		if upd, err := r.store.MergeMessageData(ctx, card.ID, map[string]any{"status": "skipped"}); err == nil {
			r.chat(c, Event{Type: "message_update", BotID: card.BotID, GroupID: card.GroupID, Data: upd})
		}
	}
}

// autoDecisions adds approvals for paused calls the approval policy would
// have let through without a card. They were only paused because they sat
// in the same model step as a call that did need one.
func autoDecisions(rc *runCtx, sess *kritrim.Session, decisions map[string]kritrim.Decision) map[string]kritrim.Decision {
	if decisions == nil {
		decisions = map[string]kritrim.Decision{}
	}
	if sess == nil {
		return decisions
	}
	for _, in := range sess.Pending {
		if _, ok := decisions[in.ID]; ok {
			continue
		}
		if cardKind(rc, in) == "" {
			decisions[in.ID] = kritrim.Approve()
		}
	}
	return decisions
}
