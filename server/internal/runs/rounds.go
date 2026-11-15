package runs

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	"gbot/internal/store"
)

// Rounds: every ask a bot sends during one run belongs to one round. When
// the run has ended and every ask in the round has too, the bot that asked
// gets one run with all the answers (see docs/protocol/bot-asks.md). The
// exchange cards are the durable record; what's here is only what a
// running process needs on top of them.

// roundTimeout is how long a round waits for its slowest answer.
var roundTimeout = 10 * time.Minute

type rounds struct {
	mu     sync.Mutex
	open   map[string]bool      // rounds whose sender is still running
	joined map[string]time.Time // rounds already handed back in this process, and when
	roots  map[string]*trace    // per request from the person
}

// trace is what one request from the person set off.
type trace struct {
	start  time.Time
	seen   time.Time
	runs   int
	asks   int
	depth  int
	tokens int
}

func newRounds() *rounds {
	return &rounds{open: map[string]bool{}, joined: map[string]time.Time{}, roots: map[string]*trace{}}
}

func newID(prefix string) string {
	var b [6]byte
	_, _ = rand.Read(b[:])
	return prefix + hex.EncodeToString(b[:])
}

// startRun counts a run against its request, starting a new request when
// the run has none.
func (rs *rounds) startRun(j *Job) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	now := time.Now()
	if j.Root == "" {
		j.Root = newID("rt_")
		// Forget requests nobody has touched for a while.
		for id, t := range rs.roots {
			if now.Sub(t.seen) > 6*time.Hour {
				delete(rs.roots, id)
			}
		}
		for id, when := range rs.joined {
			if now.Sub(when) > 6*time.Hour {
				delete(rs.joined, id)
			}
		}
	}
	t := rs.roots[j.Root]
	if t == nil {
		t = &trace{start: now}
		rs.roots[j.Root] = t
	}
	t.seen = now
	t.runs++
	t.depth = max(t.depth, j.Depth)
}

func (rs *rounds) addTokens(root string, n int) {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	if t := rs.roots[root]; t != nil {
		t.tokens += n
	}
}

// sent is how many messages a request has sent so far.
func (rs *rounds) sent(root string) int {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	if t := rs.roots[root]; t != nil {
		return t.asks
	}
	return 0
}

// take reserves one message for a request, if it has any left.
func (rs *rounds) take(root string, depth int) bool {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	t := rs.roots[root]
	if t == nil {
		t = &trace{start: time.Now(), seen: time.Now()}
		rs.roots[root] = t
	}
	if !canMessage(depth, t.asks) {
		return false
	}
	t.asks++
	t.depth = max(t.depth, depth+1)
	return true
}

func (rs *rounds) traceLine(root string) string {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	t := rs.roots[root]
	if t == nil {
		return ""
	}
	return fmt.Sprintf("root=%s runs=%d asks=%d depth=%d tokens=%d ms=%d",
		root, t.runs, t.asks, t.depth, t.tokens, time.Since(t.start).Milliseconds())
}

// openRound starts the round a run's asks go into.
func (r *Runtime) openRound(j *Job) string {
	if j.round == "" {
		j.round = newID("rd_")
		r.rounds.mu.Lock()
		r.rounds.open[j.round] = true
		r.rounds.mu.Unlock()
	}
	return j.round
}

// closeRound is called when the run that sent a round's asks ends. The
// answers go back now if they're all in; otherwise when the last one
// lands, or when the round runs out of time.
func (r *Runtime) closeRound(round string) {
	r.rounds.mu.Lock()
	delete(r.rounds.open, round)
	r.rounds.mu.Unlock()
	if !r.maybeJoin(round) {
		time.AfterFunc(roundTimeout, func() { r.expireRound(round) })
	}
}

// outbound returns the sender's card of each message in a round.
func outbound(cards []*store.Message) []*store.Message {
	var out []*store.Message
	for _, m := range cards {
		if from, _ := m.Data["from"].(string); from == m.BotID {
			out = append(out, m)
		}
	}
	return out
}

// maybeJoin hands a round's answers back to the bot that asked, once. It
// reports whether the round is settled (joined now or before).
func (r *Runtime) maybeJoin(round string) bool {
	ctx := context.Background()
	r.rounds.mu.Lock()
	if r.rounds.open[round] {
		r.rounds.mu.Unlock()
		return false
	}
	if _, done := r.rounds.joined[round]; done {
		r.rounds.mu.Unlock()
		return true
	}
	r.rounds.mu.Unlock()

	cards, err := r.store.RoundCards(ctx, round)
	if err != nil {
		r.log.Error("round cards", "err", err)
		return false
	}
	outs := outbound(cards)
	if len(outs) == 0 {
		return true
	}
	var statuses []string
	for _, m := range outs {
		if m.Data["joined"] == true {
			return true
		}
		s, _ := m.Data["status"].(string)
		statuses = append(statuses, s)
	}
	if !roundDone(statuses) {
		return false
	}
	r.rounds.mu.Lock()
	if _, done := r.rounds.joined[round]; done {
		r.rounds.mu.Unlock()
		return true
	}
	r.rounds.joined[round] = time.Now()
	r.rounds.mu.Unlock()

	first := outs[0]
	from, _ := first.Data["from"].(string)
	sender, err := r.store.GetBotAnySpace(ctx, from)
	if err != nil {
		return true // the bot that asked is gone
	}
	answers := make([]answer, 0, len(outs))
	tainted := false
	for _, m := range outs {
		to, _ := m.Data["to"].(string)
		name := "A teammate"
		if b, err := r.store.GetBotAnySpace(ctx, to); err == nil {
			name = b.Name
		}
		msg, _ := m.Data["message"].(string)
		status, _ := m.Data["status"].(string)
		reply, _ := m.Data["reply"].(string)
		answers = append(answers, answer{Name: name, Message: msg, Status: status, Reply: reply})
		tainted = tainted || m.Data["tainted"] == true
	}
	root, _ := first.Data["root"].(string)
	j := Job{Bot: sender, For: first.ChatUser, Prompt: joinPrompt(answers), Trigger: "replies",
		Untrusted: tainted, Depth: max(intOf(first.Data["depth"])-1, 0), Root: root, joinRound: round}
	if first.GroupID != "" {
		g, err := r.store.GetGroup(ctx, sender.SpaceID, first.GroupID)
		if err != nil {
			return true
		}
		j.Group, j.For = g, g.UserID
	}
	r.Enqueue(j)
	return true
}

// markJoined records on the sender's cards that the round's answers went
// back, and how long the round took. The join run calls it as it starts,
// so a restart before then joins the round again.
func (r *Runtime) markJoined(round string) {
	ctx := context.Background()
	cards, err := r.store.RoundCards(ctx, round)
	if err != nil {
		return
	}
	outs := outbound(cards)
	if len(outs) == 0 {
		return
	}
	took := time.Since(outs[0].CreatedAt).Milliseconds()
	for _, m := range outs {
		if upd, err := r.store.MergeMessageData(ctx, m.ID, map[string]any{"joined": true, "tookMs": took}); err == nil {
			r.publishCard(ctx, upd)
		}
	}
	root, _ := outs[0].Data["root"].(string)
	if line := r.rounds.traceLine(root); line != "" {
		r.log.Info("trace " + line)
	}
}

// expireRound gives up on the asks of a round still out after the round
// timeout, and hands back what came in.
func (r *Runtime) expireRound(round string) {
	ctx := context.Background()
	cards, err := r.store.RoundCards(ctx, round)
	if err != nil {
		return
	}
	for _, m := range cards {
		if s, _ := m.Data["status"].(string); !askEnded(s) {
			if upd, err := r.store.MergeMessageData(ctx, m.ID, map[string]any{"status": "expired"}); err == nil {
				r.publishCard(ctx, upd)
			}
		}
	}
	r.maybeJoin(round)
}

// publishCard shows a changed exchange card in the chat it belongs to.
func (r *Runtime) publishCard(ctx context.Context, m *store.Message) {
	b, err := r.store.GetBotAnySpace(ctx, m.BotID)
	if err != nil {
		return
	}
	c := newConvo(b.SpaceID, m.BotID, "", m.ChatUser)
	if m.GroupID != "" {
		g, err := r.store.GetGroup(ctx, b.SpaceID, m.GroupID)
		if err != nil {
			return
		}
		c = newConvo(g.SpaceID, "", g.ID, g.UserID)
	}
	r.chat(c, Event{Type: "message_update", BotID: m.BotID, GroupID: m.GroupID, Data: m})
}

// recoverAsks picks up what a previous process left: messages nobody
// answered are put back in line, and rounds that never went back are
// handed back (or wait for their last answer).
func (r *Runtime) recoverAsks(ctx context.Context) {
	open, err := r.store.OpenAsks(ctx)
	if err != nil {
		r.log.Error("ask recovery", "err", err)
		return
	}
	for _, in := range open {
		ask, _ := in.Data["ask"].(string)
		pair, err := r.store.AskCards(ctx, ask)
		if err != nil || len(pair) != 2 {
			continue
		}
		from, _ := in.Data["from"].(string)
		sender, err1 := r.store.GetBotAnySpace(ctx, from)
		receiver, err2 := r.store.GetBotAnySpace(ctx, in.BotID)
		if err1 != nil || err2 != nil {
			continue
		}
		out := pair[0]
		if out.ID == in.ID {
			out = pair[1]
		}
		expect, _ := in.Data["expect"].(string)
		msg, _ := in.Data["message"].(string)
		root, _ := in.Data["root"].(string)
		// Where the text came from is lost with the process; treat it as
		// outside text.
		r.Enqueue(Job{Bot: receiver, For: in.ChatUser, Prompt: askPrompt(sender.Name, msg, expect), Trigger: "bot",
			Untrusted: true, Depth: intOf(in.Data["depth"]), Root: root, exchange: []*store.Message{out, in}})
	}
	rounds, err := r.store.UnjoinedRounds(ctx)
	if err != nil {
		r.log.Error("round recovery", "err", err)
		return
	}
	for _, id := range rounds {
		r.closeRound(id)
	}
	if n := len(open) + len(rounds); n > 0 {
		r.log.Info("resumed bot asks", "asks", len(open), "rounds", len(rounds))
	}
}

// askPrompt is what the receiving bot is given.
func askPrompt(from, message, expect string) string {
	if expect == "tell" {
		return fmt.Sprintf("Message from %s, another of the person's bots, passing something on. Nothing you write goes back to %s. If it asks you to do something, do it; otherwise reply with just %s.\n\n%s", from, from, noReply, message)
	}
	return fmt.Sprintf("Message from %s, another of the person's bots. %s is waiting for your answer: write it as your reply here, and it goes back to %s, not to the person. Answer the question; don't ask %s anything back.\n\n%s", from, from, from, from, message)
}

func intOf(v any) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	}
	return 0
}
