package runs

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/ganeshdipdumbare/kritrim"

	"gbot/internal/store"
)

// The queue keeps the order people see in a chat. Chat, routine, webhook
// and card-resume runs are also written to run_queue, so a restart
// resumes them; handoffs and bot-to-bot messages carry derived state and
// stay only in memory.

func queueable(trigger string) bool {
	switch trigger {
	case "chat", "routine", "webhook", "resume", "slack":
		return true
	}
	return false
}

func decisionMap(d map[string]kritrim.Decision) map[string]any {
	if len(d) == 0 {
		return nil
	}
	raw, _ := json.Marshal(d)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return out
}

func (r *Runtime) Enqueue(j Job) { r.enqueue(j, false) }

func (r *Runtime) enqueue(j Job, recovered bool) {
	k := j.key()
	if !recovered && queueable(j.Trigger) && j.Bot != nil {
		groupID := ""
		chatUser := j.For
		if j.Group != nil {
			groupID, chatUser = j.Group.ID, ""
		}
		q := &store.QueueItem{
			SpaceID: j.Bot.SpaceID, BotID: j.Bot.ID, GroupID: groupID, ChatUser: chatUser,
			Prompt: j.Prompt, Trigger: j.Trigger, Untrusted: j.Untrusted, Depth: j.Depth,
			Thread: j.Thread, Decisions: decisionMap(j.Decisions),
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		if err := r.store.Enqueue(ctx, q); err != nil {
			r.log.Error("queue record", "err", err)
		} else {
			j.QueueID = q.ID
		}
		cancel()
	}
	// Publish before the lane starts: the client sees the item waiting,
	// then the running update that follows.
	if j.QueueID != "" {
		r.broadcastQueue(j.convo(), j.Bot.ID, groupIDOf(&j))
	}
	r.mu.Lock()
	if w := r.workers[k]; w != nil {
		w.queue = append(w.queue, j)
		r.mu.Unlock()
	} else {
		w := &worker{}
		r.workers[k] = w
		r.mu.Unlock()
		go r.drain(k, j, w)
	}
}

func groupIDOf(j *Job) string {
	if j.Group != nil {
		return j.Group.ID
	}
	return ""
}

func (r *Runtime) drain(k string, j Job, w *worker) {
	for {
		timeout := r.cfg.RunTimeout
		if timeout <= 0 {
			timeout = 30 * time.Minute
		}
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		r.mu.Lock()
		w.cancel = cancel
		r.mu.Unlock()
		if r.claim(&j) {
			r.run(ctx, &j)
		}
		cancel()
		r.mu.Lock()
		if len(w.queue) == 0 {
			delete(r.workers, k)
			r.mu.Unlock()
			return
		}
		j, w.queue = w.queue[0], w.queue[1:]
		r.mu.Unlock()
	}
}

// claim moves a running item from queued to running. A paused, cancelled
// or already finished item is skipped. Every job, queued or internal (a
// bot-to-bot ask, a reply, a group handoff), runs only while its person can
// still use the bot: a removed team member's work is dropped, not run with
// their own credentials. A failed lookup also drops the job.
func (r *Runtime) claim(j *Job) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := r.store.GetBotFor(ctx, j.Bot.SpaceID, j.user(), j.Bot.ID); err != nil {
		// An internal job (a bot-to-bot ask, its replies, a hand-off) has
		// state waiting on it in memory, so only a definite "no access"
		// drops it; a passing database error lets it run.
		if j.QueueID == "" && !errors.Is(err, store.ErrNotFound) {
			r.log.Error("claim access check", "err", err)
			return true
		}
		if j.QueueID != "" {
			_ = r.store.SetQueueStatus(ctx, j.QueueID, "cancelled", "you no longer have access to this bot")
		}
		return false
	}
	if j.QueueID == "" {
		return true
	}
	q, err := r.store.QueueItem(ctx, j.QueueID)
	if err != nil {
		return true // the record is best effort; access was checked above
	}
	if q.Status != "queued" {
		return false
	}
	if err := r.store.SetQueueStatus(ctx, j.QueueID, "running", ""); err != nil {
		r.log.Error("queue claim", "err", err)
	}
	r.broadcastQueue(j.convo(), j.Bot.ID, groupIDOf(j))
	return true
}

func (r *Runtime) finishQueue(j *Job, status, errText string) {
	if j.QueueID == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// A person may have cancelled or cleared it while the turn finished.
	if cur, err := r.store.QueueItem(ctx, j.QueueID); err == nil && (cur.Status == "cancelled" || cur.Status == "stopped") {
		return
	}
	if err := r.store.SetQueueStatus(ctx, j.QueueID, status, errText); err != nil {
		r.log.Error("queue finish", "err", err)
	}
	r.broadcastQueue(j.convo(), j.Bot.ID, groupIDOf(j))
}

func (r *Runtime) broadcastQueue(c convo, botID, groupID string) {
	items, err := r.store.ActiveQueue(context.Background(), botID, c.User, groupID)
	if err != nil {
		return
	}
	r.chat(c, Event{Type: "queue", BotID: botID, GroupID: groupID, Data: items})
}

// QueueSnapshot is the active queue of one chat, for the API's first read.
func (r *Runtime) QueueSnapshot(ctx context.Context, botID, userID, groupID string) ([]*store.QueueItem, error) {
	return r.store.ActiveQueue(ctx, botID, userID, groupID)
}

func (r *Runtime) queueConvo(q *store.QueueItem) convo {
	if q.GroupID != "" {
		if g, err := r.store.GetGroup(context.Background(), q.SpaceID, q.GroupID); err == nil {
			return newConvo(g.SpaceID, "", g.ID, g.UserID)
		}
	}
	return newConvo(q.SpaceID, q.BotID, "", q.ChatUser)
}

// Stop cancels one chat's current turn. Messages queued behind it stay,
// so the person can still cancel or pause them one by one.
func (r *Runtime) Stop(key string) bool {
	return r.stopWhere(func(k string) bool { return k == key })
}

// StopBot cancels every person's turn with a bot and drops its queue.
func (r *Runtime) StopBot(botID string) bool {
	stopped := r.stopWhere(func(k string) bool { return strings.HasPrefix(k, "b:"+botID+":") })
	_ = r.store.CancelQueueBot(context.Background(), botID)
	return stopped
}

func (r *Runtime) stopWhere(match func(key string) bool) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	stopped := false
	for k, w := range r.workers {
		if !match(k) {
			continue
		}
		stopped = true
		if w.cancel != nil {
			w.cancel()
		}
	}
	return stopped
}

// ClearQueue cancels everything waiting behind the current turn. The turn
// itself keeps going; Stop (on the composer) is what interrupts it.
func (r *Runtime) ClearQueue(ctx context.Context, spaceID, botID, userID, groupID string) error {
	key := ChatKey(botID, userID)
	if groupID != "" {
		key = GroupKey(groupID)
	}
	r.mu.Lock()
	if w := r.workers[key]; w != nil {
		w.queue = nil
	}
	r.mu.Unlock()
	if err := r.store.CancelQueue(ctx, botID, userID, groupID); err != nil {
		return err
	}
	c := newConvo(spaceID, botID, "", userID)
	if groupID != "" {
		if g, err := r.store.GetGroup(ctx, spaceID, groupID); err == nil {
			c = newConvo(g.SpaceID, "", g.ID, g.UserID)
		}
	}
	r.broadcastQueue(c, botID, groupID)
	return nil
}

// CancelQueueItem cancels one queued or paused message, or stops the turn
// if that is the item running now.
func (r *Runtime) CancelQueueItem(ctx context.Context, q *store.QueueItem) error {
	if q.Status == "running" {
		key := ChatKey(q.BotID, q.ChatUser)
		if q.GroupID != "" {
			key = GroupKey(q.GroupID)
		}
		r.stopWhere(func(k string) bool { return k == key })
	}
	if err := r.store.SetQueueStatus(ctx, q.ID, "cancelled", ""); err != nil {
		return err
	}
	r.removeQueued(q)
	r.broadcastQueue(r.queueConvo(q), q.BotID, q.GroupID)
	return nil
}

// PauseQueueItem holds one message until the person resumes it.
func (r *Runtime) PauseQueueItem(ctx context.Context, q *store.QueueItem) error {
	if q.Status == "running" {
		return errors.New("that message is already running")
	}
	if err := r.store.SetQueueStatus(ctx, q.ID, "paused", ""); err != nil {
		return err
	}
	r.removeQueued(q)
	r.broadcastQueue(r.queueConvo(q), q.BotID, q.GroupID)
	return nil
}

// ResumeQueueItem puts a paused message back in line.
func (r *Runtime) ResumeQueueItem(ctx context.Context, q *store.QueueItem) error {
	if q.Status != "paused" {
		return errors.New("that message is not paused")
	}
	j, err := r.jobFromQueue(ctx, q)
	if err != nil {
		_ = r.store.SetQueueStatus(ctx, q.ID, "error", err.Error())
		return err
	}
	if err := r.store.SetQueueStatus(ctx, q.ID, "queued", ""); err != nil {
		return err
	}
	r.enqueue(*j, true)
	return nil
}

func (r *Runtime) removeQueued(q *store.QueueItem) {
	key := ChatKey(q.BotID, q.ChatUser)
	if q.GroupID != "" {
		key = GroupKey(q.GroupID)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	w := r.workers[key]
	if w == nil {
		return
	}
	out := w.queue[:0]
	for _, j := range w.queue {
		if j.QueueID != q.ID {
			out = append(out, j)
		}
	}
	w.queue = out
}

// Recover resumes what a previous process left queued. Interrupted turns
// are marked failed: their prompt is still in the chat.
func (r *Runtime) Recover(ctx context.Context) {
	if err := r.store.FailRunningQueue(ctx); err != nil {
		r.log.Error("queue recovery", "err", err)
	}
	items, err := r.store.RecoverQueue(ctx)
	if err != nil {
		r.log.Error("queue recovery", "err", err)
		return
	}
	for _, q := range items {
		j, err := r.jobFromQueue(ctx, q)
		if err != nil {
			_ = r.store.SetQueueStatus(ctx, q.ID, "error", err.Error())
			continue
		}
		r.enqueue(*j, true)
	}
	if len(items) > 0 {
		r.log.Info("resumed queued messages", "count", len(items))
	}
	r.recoverAsks(ctx)
}

func (r *Runtime) jobFromQueue(ctx context.Context, q *store.QueueItem) (*Job, error) {
	b, err := r.store.GetBotAnySpace(ctx, q.BotID)
	if err != nil {
		return nil, errors.New("its bot is gone")
	}
	j := &Job{
		Bot: b, For: q.ChatUser, Prompt: q.Prompt, Trigger: q.Trigger, Untrusted: q.Untrusted,
		Depth: q.Depth, Thread: q.Thread, QueueID: q.ID,
	}
	if q.GroupID != "" {
		g, err := r.store.GetGroup(ctx, q.SpaceID, q.GroupID)
		if err != nil {
			return nil, errors.New("its group is gone")
		}
		j.Group, j.For = g, g.UserID
	}
	if len(q.Decisions) > 0 {
		raw, _ := json.Marshal(q.Decisions)
		var d map[string]kritrim.Decision
		if json.Unmarshal(raw, &d) == nil {
			j.Decisions = d
		}
	}
	return j, nil
}

// ClearChat wipes one person's chat with a bot and starts it fresh: the
// turn running now is stopped, what's queued is dropped, and the model
// thread goes with the messages. memory also forgets what the bot learned
// in this chat. Every device of that person is told to empty the chat.
func (r *Runtime) ClearChat(ctx context.Context, b *store.Bot, userID string, memory bool) error {
	r.Stop(ChatKey(b.ID, userID))
	if err := r.ClearQueue(ctx, b.SpaceID, b.ID, userID, ""); err != nil {
		return err
	}
	// A summary still being written would bring the old chat back.
	r.stopSummary(threadID(b, userID))
	if _, err := r.store.ClearChat(ctx, b.ID, userID, threadID(b, userID), memory); err != nil {
		return err
	}
	c := newConvo(b.SpaceID, b.ID, "", userID)
	r.chat(c, Event{Type: "cleared", BotID: b.ID, Data: map[string]bool{"memory": memory}})
	r.setPresence(c, b, Presence{State: "idle"})
	return nil
}
