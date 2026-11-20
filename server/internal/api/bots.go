package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/publicsuffix"

	"gbot/internal/runs"
	"gbot/internal/store"
)

func (s *Server) listBots(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	bots, err := s.Store.ListBots(r.Context(), u.SpaceID, u.ID)
	if err != nil {
		s.Log.Error("list bots", "err", err)
		writeErr(w, 500, "could not load bots")
		return
	}
	if bots == nil {
		bots = []*store.Bot{}
	}
	// Presence only for bots the person can see.
	visible := map[string]bool{}
	for _, b := range bots {
		visible[b.ID] = true
	}
	presence := map[string]runs.Presence{}
	for id, p := range s.Runtime.PresenceSnapshot() {
		if visible[id] {
			presence[id] = p
		}
	}
	writeJSON(w, 200, map[string]any{"bots": bots, "presence": presence})
}

// bot loads the path's bot if the caller can use it. Another space's bot,
// or one that isn't shared with them, is indistinguishable from a missing one.
func (s *Server) bot(w http.ResponseWriter, r *http.Request) *store.Bot {
	u := userFrom(r)
	b, err := s.Store.GetBotFor(r.Context(), u.SpaceID, u.ID, r.PathValue("id"))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) || strings.Contains(err.Error(), "invalid input syntax") {
			writeErr(w, 404, "bot not found")
		} else {
			s.Log.Error("get bot", "err", err)
			writeErr(w, 500, "could not load bot")
		}
		return nil
	}
	return b
}

// manage loads a bot the caller owns or manages, for changes to how it works.
func (s *Server) manage(w http.ResponseWriter, r *http.Request) *store.Bot {
	b := s.bot(w, r)
	if b == nil {
		return nil
	}
	if b.Role != "owner" && b.Role != "manager" {
		writeErr(w, 403, "only the owner or a manager can change this bot")
		return nil
	}
	return b
}

func (s *Server) getBot(w http.ResponseWriter, r *http.Request) {
	if b := s.bot(w, r); b != nil {
		writeJSON(w, 200, b)
	}
}

var hostPattern = regexp.MustCompile(`^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$`)

// cleanHosts normalizes a browser allowlist. Accepting "https://x/y" is
// friendly, but only the host survives; subdomains are covered by the
// suffix match in the browser.
func cleanHosts(in []string) ([]string, string) {
	if len(in) > 50 {
		return nil, "keep the allowlist to 50 sites"
	}
	out := []string{}
	seen := map[string]bool{}
	for _, raw := range in {
		h := strings.ToLower(strings.TrimSpace(raw))
		if i := strings.Index(h, "://"); i >= 0 {
			h = h[i+3:]
		}
		if i := strings.IndexAny(h, "/?#"); i >= 0 {
			h = h[:i]
		}
		h = strings.TrimPrefix(h, "*.")
		h = strings.TrimSuffix(h, ".")
		if h == "" {
			continue
		}
		if !hostPattern.MatchString(h) || (!strings.Contains(h, ".") && h != "localhost") {
			return nil, fmt.Sprintf("%q isn't a domain like linkedin.com", raw)
		}
		if h != "localhost" {
			base, err := publicsuffix.EffectiveTLDPlusOne(h)
			if err != nil {
				return nil, fmt.Sprintf("%q isn't a domain like linkedin.com", raw)
			}
			h = base
		}
		if !seen[h] {
			seen[h] = true
			out = append(out, h)
		}
	}
	return out, ""
}

func (s *Server) createBot(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name         string     `json:"name"`
		Title        string     `json:"title"`
		Instructions string     `json:"instructions"`
		Model        string     `json:"model"`
		Look         store.Look `json:"look"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if msg := store.ValidateBotFields(&body.Name, &body.Title, &body.Instructions, &body.Look); msg != "" {
		writeErr(w, 400, msg)
		return
	}
	u := userFrom(r)
	if body.Model != "" && s.Models.PickFor(r.Context(), s.userProviders(r.Context(), u.ID), body.Model) != body.Model {
		writeErr(w, 400, "that model isn't available")
		return
	}
	b, err := s.Store.CreateBot(r.Context(), u.SpaceID, u.ID, store.NewBot{
		Name: body.Name, Title: body.Title, Instructions: body.Instructions, Model: body.Model, Look: body.Look,
	})
	if err != nil {
		if errors.Is(err, store.ErrBotLimit) {
			writeErr(w, 409, err.Error())
			return
		}
		s.Log.Error("create bot", "err", err)
		writeErr(w, 500, "could not create bot")
		return
	}
	// A new bot is private to its owner until they share it.
	s.Hub.Publish(u.SpaceID, []string{u.ID}, runs.Event{Type: "bot", BotID: b.ID, Data: b})
	writeJSON(w, 201, b)
}

func (s *Server) updateBot(w http.ResponseWriter, r *http.Request) {
	var p store.BotPatch
	if !readJSON(w, r, &p) {
		return
	}
	if msg := store.ValidateBotFields(p.Name, p.Title, p.Instructions, p.Look); msg != "" {
		writeErr(w, 400, msg)
		return
	}
	if p.AllowedHosts != nil {
		hosts, msg := cleanHosts(*p.AllowedHosts)
		if msg != "" {
			writeErr(w, 400, msg)
			return
		}
		p.AllowedHosts = &hosts
	}
	if p.Model != nil && *p.Model != "" && s.Models.PickFor(r.Context(), s.userProviders(r.Context(), userFrom(r).ID), *p.Model) != *p.Model {
		writeErr(w, 400, "that model isn't available")
		return
	}
	cur := s.manage(w, r)
	if cur == nil {
		return
	}
	b, err := s.Store.UpdateBot(r.Context(), cur.SpaceID, cur.ID, p)
	if err != nil {
		writeErr(w, 404, "bot not found")
		return
	}
	s.Hub.Publish(b.SpaceID, s.Runtime.Audience(b.ID), runs.Event{Type: "bot", BotID: b.ID, Data: b})
	writeJSON(w, 200, b)
}

// archiveBot deletes a bot for everyone. Only its owner can.
func (s *Server) archiveBot(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	if b.Role != "owner" {
		writeErr(w, 403, "only the owner can delete this bot")
		return
	}
	// The main bot stays: it is what the person comes back to, and the
	// only bot that can hatch the others.
	if b.Main {
		writeErr(w, 400, "the main bot can't be deleted")
		return
	}
	audience := s.Runtime.Audience(b.ID)
	// Everyone who could have a computer with this bot. Adopted computers
	// do not remember their owner, so their keys are derived from these.
	people, _ := s.Store.SpaceMemberIDs(r.Context(), b.SpaceID)
	people = append(people, b.OwnerID)
	if err := s.Store.ArchiveBot(r.Context(), b.SpaceID, b.ID); err != nil {
		if errors.Is(err, store.ErrMainBot) {
			writeErr(w, 400, "the main bot can't be deleted")
		} else {
			writeErr(w, 404, "bot not found")
		}
		return
	}
	s.Runtime.StopBot(b.ID)
	if s.Computer != nil {
		s.Computer.StopBot(r.Context(), b.ID, people)
	}
	s.Hub.Publish(b.SpaceID, audience, runs.Event{Type: "bot_removed", BotID: b.ID})
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// clearChat deletes the caller's own chat with a bot; other people's chats
// with a shared bot are untouched. ?memory=1 also forgets what the bot
// learned in it.
func (s *Server) clearChat(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	memory := r.URL.Query().Get("memory") == "1"
	if err := s.Runtime.ClearChat(r.Context(), b, userFrom(r).ID, memory); err != nil {
		s.Log.Error("clear chat", "err", err)
		writeErr(w, 500, "could not clear the chat")
		return
	}
	writeJSON(w, 200, map[string]bool{"cleared": true, "memory": memory})
}

func (s *Server) listMessages(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	var before *time.Time
	if v := r.URL.Query().Get("before"); v != "" {
		t, err := time.Parse(time.RFC3339Nano, v)
		if err != nil {
			writeErr(w, 400, "bad cursor")
			return
		}
		before = &t
	}
	// Each person sees only their own chat with the bot.
	msgs, err := s.Store.ListMessages(r.Context(), b.ID, userFrom(r).ID, before, 100)
	if err != nil {
		writeErr(w, 500, "could not load messages")
		return
	}
	if msgs == nil {
		msgs = []*store.Message{}
	}
	writeJSON(w, 200, map[string]any{"messages": msgs})
}

func (s *Server) sendMessage(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	u := userFrom(r)
	if !s.msgLimit.Allow(u.ID) {
		writeErr(w, http.StatusTooManyRequests, "slow down a little")
		return
	}
	var body struct {
		Text string `json:"text"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	body.Text = strings.TrimSpace(body.Text)
	if body.Text == "" || len(body.Text) > 32000 {
		writeErr(w, 400, "message must be 1 to 32000 characters")
		return
	}
	m := &store.Message{BotID: b.ID, Role: "user", Content: body.Text, ChatUser: u.ID}
	if err := s.Store.AddMessage(r.Context(), m, &u.ID); err != nil {
		writeErr(w, 500, "could not send")
		return
	}
	s.Hub.Publish(u.SpaceID, []string{u.ID}, runs.Event{Type: "message", BotID: b.ID, Data: m})
	s.Runtime.Enqueue(runs.Job{Bot: b, For: u.ID, Prompt: body.Text, Trigger: "chat"})
	writeJSON(w, 201, m)
}

func (s *Server) stopBot(w http.ResponseWriter, r *http.Request) {
	if b := s.bot(w, r); b != nil {
		writeJSON(w, 200, map[string]bool{"stopped": s.Runtime.Stop(runs.ChatKey(b.ID, userFrom(r).ID))})
	}
}

// events streams the events meant for the caller over SSE.
func (s *Server) events(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		writeErr(w, 500, "streaming unsupported")
		return
	}
	u := userFrom(r)
	ch, unsub := s.Hub.Subscribe(u.SpaceID, u.ID)
	defer unsub()
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	// no-transform stops proxies such as Cloudflare from compressing (and
	// so buffering) the stream; X-Accel-Buffering does the same for nginx.
	h.Set("Cache-Control", "no-store, no-transform")
	h.Set("X-Accel-Buffering", "no")
	// The hello lets clients detect a path that buffers the stream and
	// switch to long-polling.
	fmt.Fprint(w, "retry: 1500\ndata: {\"type\":\"hello\"}\n\n")
	fl.Flush()
	ping := time.NewTicker(20 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ping.C:
			if !s.stillAllowed(r, "") {
				return
			}
			fmt.Fprint(w, ": ping\n\n")
			fl.Flush()
		case b, ok := <-ch:
			if !ok {
				return // dropped as too slow; the client reconnects
			}
			fmt.Fprintf(w, "data: %s\n\n", b)
			fl.Flush()
		}
	}
}

// pollEvents is the long-polling alternative to the event stream: it
// returns the caller's events after ?after=N, waiting up to 20s for one.
func (s *Server) pollEvents(w http.ResponseWriter, r *http.Request) {
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	u := userFrom(r)
	events, next, reset := s.Hub.Poll(r.Context(), u.SpaceID, u.ID, after, 20*time.Second)
	if events == nil {
		events = []json.RawMessage{}
	}
	writeJSON(w, 200, map[string]any{"events": events, "next": next, "reset": reset})
}

// blob serves a picture a bot's tool handed back. Anyone who may open
// that bot's chat may see it; a picture with no bot belongs to the person
// who asked for it.
func (s *Server) blob(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	b, err := s.Store.Blob(r.Context(), r.PathValue("id"))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) || strings.Contains(err.Error(), "invalid input syntax") {
			writeErr(w, 404, "not found")
			return
		}
		s.Log.Error("blob", "err", err)
		writeErr(w, 500, "could not load it")
		return
	}
	switch {
	case b.BotID != "":
		if _, err := s.Store.GetBotFor(r.Context(), u.SpaceID, u.ID, b.BotID); err != nil {
			writeErr(w, 404, "not found")
			return
		}
	case b.User != u.ID:
		writeErr(w, 404, "not found")
		return
	}
	h := w.Header()
	h.Set("Content-Type", b.Mime)
	// Opened on its own, it is still only a picture: no script, no
	// network, nothing that could act as this origin.
	h.Set("Content-Security-Policy", "default-src 'none'; img-src 'self'; sandbox")
	// The bytes never change, and the id is unguessable.
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("Content-Disposition", "inline")
	h.Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(b.Bytes)
}
