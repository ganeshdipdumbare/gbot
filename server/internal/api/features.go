package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5/pgconn"

	"gbot/internal/computer"
	"gbot/internal/cron"
	"gbot/internal/routines"
	"gbot/internal/runs"
	"gbot/internal/store"
)

func (s *Server) routesV2(mux *http.ServeMux) {
	mux.Handle("GET /api/vault", s.authed(s.listVault))
	mux.Handle("POST /api/vault", s.authed(s.createVault))
	mux.Handle("PATCH /api/vault/{id}", s.authed(s.updateVault))
	mux.Handle("DELETE /api/vault/{id}", s.authed(s.deleteVault))

	mux.Handle("GET /api/settings/rules", s.authed(s.listRules))
	mux.Handle("PUT /api/settings/rules", s.authed(s.setRule))
	mux.Handle("POST /api/cards/{id}", s.authed(s.answerCard))

	mux.Handle("GET /api/bots/{id}/computer", s.authed(s.computerStatus))
	mux.Handle("POST /api/bots/{id}/computer/{action}", s.authed(s.computerAction))
	mux.Handle("GET /api/bots/{id}/computer/screen", s.authed(s.computerScreen))
	mux.Handle("GET /api/bots/{id}/computer/stream", s.authed(s.computerStream))

	mux.Handle("GET /api/bots/{id}/routines", s.authed(s.listRoutines))
	mux.Handle("POST /api/bots/{id}/routines", s.authed(s.createRoutine))
	mux.Handle("PATCH /api/bots/{id}/routines/{rid}", s.authed(s.updateRoutine))
	mux.Handle("DELETE /api/bots/{id}/routines/{rid}", s.authed(s.deleteRoutine))
	mux.Handle("POST /api/bots/{id}/routines/{rid}/run", s.authed(s.runRoutine))
	mux.HandleFunc("POST /hooks/{rid}", s.webhook)

	mux.Handle("GET /api/providers", s.authed(s.listProviders))
	mux.Handle("POST /api/providers", s.authed(s.createProvider))
	mux.Handle("POST /api/providers/discover", s.authed(s.discoverProvider))
	mux.Handle("PATCH /api/providers/{id}", s.authed(s.updateProvider))
	mux.Handle("DELETE /api/providers/{id}", s.authed(s.deleteProvider))
	mux.Handle("POST /api/providers/{id}/refresh", s.authed(s.refreshProvider))

	mux.Handle("GET /api/mcp", s.authed(s.listMCP))
	mux.Handle("POST /api/mcp", s.authed(s.createMCP))
	mux.Handle("POST /api/mcp/oauth/start", s.authed(s.startMCPOAuth))
	mux.HandleFunc("GET /api/mcp/oauth/callback", s.mcpOAuthCallback)
	mux.Handle("PATCH /api/mcp/{id}", s.authed(s.toggleMCP))
	mux.Handle("DELETE /api/mcp/{id}", s.authed(s.deleteMCP))
	mux.Handle("GET /api/skills", s.authed(s.listSkills))
	mux.Handle("POST /api/skills", s.authed(s.saveSkill))
	mux.Handle("DELETE /api/skills/{id}", s.authed(s.deleteSkill))

	mux.Handle("GET /api/bots/{id}/queue", s.authed(s.botQueue))
	mux.Handle("POST /api/bots/{id}/queue/clear", s.authed(s.clearBotQueue))
	mux.Handle("GET /api/groups/{id}/queue", s.authed(s.groupQueue))
	mux.Handle("POST /api/groups/{id}/queue/clear", s.authed(s.clearGroupQueue))
	mux.Handle("POST /api/queue/{qid}", s.authed(s.queueAction))

	mux.Handle("GET /api/groups", s.authed(s.listGroups))
	mux.Handle("POST /api/groups", s.authed(s.createGroup))
	mux.Handle("DELETE /api/groups/{id}", s.authed(s.archiveGroup))
	mux.Handle("GET /api/groups/{id}/messages", s.authed(s.groupMessages))
	mux.Handle("POST /api/groups/{id}/messages", s.authed(s.sendGroupMessage))
	mux.Handle("POST /api/groups/{id}/stop", s.authed(s.stopGroup))

	mux.Handle("GET /api/voice", s.authed(s.voiceConfig))
	mux.Handle("POST /api/voice/transcribe", s.authed(s.transcribe))
	mux.Handle("POST /api/voice/speak", s.authed(s.speak))
}

// ---- Approval settings and cards ----

func (s *Server) listRules(w http.ResponseWriter, r *http.Request) {
	rules, err := s.Store.ToolRules(r.Context(), userFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "could not load rules")
		return
	}
	if rules == nil {
		rules = []store.ToolRule{}
	}
	writeJSON(w, 200, map[string]any{"rules": rules})
}

func (s *Server) setRule(w http.ResponseWriter, r *http.Request) {
	var body store.ToolRule
	if !readJSON(w, r, &body) {
		return
	}
	if body.Tool == "" || len(body.Tool) > 200 || (body.Rule != "" && body.Rule != "allow" && body.Rule != "block") {
		writeErr(w, 400, "rule is allow, block, or empty to remove")
		return
	}
	if err := s.Store.SetToolRule(r.Context(), userFrom(r).ID, body.Tool, body.Rule); err != nil {
		writeErr(w, 500, "could not save")
		return
	}
	s.listRules(w, r)
}

func (s *Server) answerCard(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Decision string `json:"decision"` // approve | reject | answer
		Text     string `json:"text"`
		Always   bool   `json:"always"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	u := userFrom(r)
	card, err := s.Store.GetMessage(r.Context(), r.PathValue("id"))
	if err != nil || card.Role != "card" {
		writeErr(w, 404, "card not found")
		return
	}
	// The card must sit in a chat the caller can see: their own chat with
	// a bot they can use, or a group they made.
	var group *store.Group
	if card.GroupID != "" {
		if group, err = s.Store.GroupFor(r.Context(), u.SpaceID, u.ID, card.GroupID); err != nil {
			writeErr(w, 404, "card not found")
			return
		}
	} else if card.ChatUser != u.ID {
		writeErr(w, 404, "card not found")
		return
	}
	bot, err := s.Store.GetBotFor(r.Context(), u.SpaceID, u.ID, card.BotID)
	if err != nil {
		writeErr(w, 404, "card not found")
		return
	}
	if len(body.Text) > 4000 {
		writeErr(w, 400, "answer is too long")
		return
	}
	upd, err := s.Runtime.Answer(r.Context(), u, card, bot, group, body.Decision, strings.TrimSpace(body.Text), body.Always)
	if err != nil {
		code := 400
		switch {
		case errors.Is(err, runs.ErrCardDone):
			code = 409
		case errors.Is(err, runs.ErrNotRequester):
			code = 403
		}
		writeErr(w, code, err.Error())
		return
	}
	writeJSON(w, 200, upd)
}

// ---- The bot's computer ----

func (s *Server) needComputer(w http.ResponseWriter) bool {
	if s.Computer == nil {
		writeErr(w, 503, "computers are turned off on this server")
		return false
	}
	return true
}

// computerOf is the caller's own computer with the bot: each person who
// uses a shared bot has a separate one.
func (s *Server) computerOf(w http.ResponseWriter, r *http.Request) (*store.Bot, computer.Owner, bool) {
	b := s.bot(w, r)
	if b == nil || !s.needComputer(w) {
		return nil, computer.Owner{}, false
	}
	return b, computer.Owner{Bot: b.ID, User: userFrom(r).ID, Hosts: b.AllowedHosts}, true
}

func (s *Server) computerStatus(w http.ResponseWriter, r *http.Request) {
	b, o, ok := s.computerOf(w, r)
	if !ok {
		return
	}
	st := s.Computer.Status(r.Context(), o)
	// Status repairs an adopted box's owner; refresh after it.
	s.Runtime.RefreshLive(b.SpaceID, b.ID)
	writeJSON(w, 200, st)
}

func (s *Server) computerAction(w http.ResponseWriter, r *http.Request) {
	b, o, ok := s.computerOf(w, r)
	if !ok {
		return
	}
	var err error
	switch r.PathValue("action") {
	case "start":
		err = s.Computer.Ensure(r.Context(), o, b.Network)
	case "stop":
		s.Computer.Stop(r.Context(), o)
	case "sleep":
		// Freeze the screen only once the turn using it has stopped, so
		// no tool call is left mid-step on a sleeping guest.
		s.Runtime.Stop(runs.ChatKey(b.ID, userFrom(r).ID))
		err = s.Computer.Sleep(r.Context(), o)
		if err == nil {
			s.Runtime.MarkIdle(b.SpaceID, b.ID)
		}
	case "wake":
		err = s.Computer.Wake(r.Context(), o)
		if errors.Is(err, computer.ErrNotRunning) {
			// It was stopped, not asleep; start it fresh.
			err = s.Computer.Ensure(r.Context(), o, b.Network)
		}
	case "control":
		var body struct {
			User bool `json:"user"`
		}
		if !readJSON(w, r, &body) {
			return
		}
		if body.User {
			// A person taking the screen stops the bot's turn, including a
			// computer-use task that would otherwise fight them for the mouse.
			s.Runtime.Stop(runs.ChatKey(b.ID, userFrom(r).ID))
			if err = s.Computer.Ensure(r.Context(), o, b.Network); err == nil {
				err = s.Computer.TakeControl(o, true)
			}
		} else {
			err = s.Computer.TakeControl(o, false)
		}
	case "input":
		var in computer.Input
		if !readJSON(w, r, &in) {
			return
		}
		err = s.Computer.Send(r.Context(), o, in)
	case "show":
		var body struct {
			App string `json:"app"`
		}
		if !readJSON(w, r, &body) {
			return
		}
		if err = s.Computer.Ensure(r.Context(), o, b.Network); err == nil {
			err = s.Computer.Show(r.Context(), o, body.App)
		}
	default:
		writeErr(w, 404, "not found")
		return
	}
	if err != nil {
		writeErr(w, 409, err.Error())
		return
	}
	writeJSON(w, 200, s.Computer.Status(r.Context(), o))
}

func (s *Server) computerScreen(w http.ResponseWriter, r *http.Request) {
	_, o, ok := s.computerOf(w, r)
	if !ok {
		return
	}
	img, err := s.Computer.Screenshot(r.Context(), o)
	if err != nil {
		writeErr(w, 409, err.Error())
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(img)
}

// computerStream sends the screen as a multipart MJPEG stream. One
// connection replaces the browser's frame-by-frame polling, so watching
// or taking control doesn't pay a request per frame.
func (s *Server) computerStream(w http.ResponseWriter, r *http.Request) {
	_, o, ok := s.computerOf(w, r)
	if !ok {
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeErr(w, 500, "streaming is not supported")
		return
	}
	if _, err := s.Computer.Screenshot(r.Context(), o); err != nil {
		writeErr(w, 409, err.Error())
		return
	}
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=gbotframe")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	// Each frame costs one exec inside the computer, so don't also wait a
	// long fixed interval; let the capture set the pace. A still screen
	// sends nothing: the browser keeps the last frame.
	// Native 1280x800 frames: sharp text matters more than the last few
	// frames a second. Input batching is what made it feel fast.
	const interval = 60 * time.Millisecond
	fails, sent := 0, 0
	var last []byte
	lastSent := time.Time{}
	for {
		img, err := s.Computer.Screenshot(r.Context(), o)
		if err == nil {
			fails = 0
			same := last != nil && bytes.Equal(last, img)
			// Send the first two frames even when identical: a multipart
			// client/proxy needs the next boundary to finish the part.
			// After that, a still screen gets a 1s heartbeat so a proxy
			// never holds a lone frame forever.
			if same && sent >= 2 && time.Since(lastSent) < time.Second {
				// nothing changed and the heartbeat isn't due
			} else {
				if !same {
					last = append(last[:0], img...)
				}
				if _, err = fmt.Fprintf(w, "--gbotframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", len(last)); err == nil {
					_, err = w.Write(append(last, '\r', '\n'))
				}
				if err != nil {
					return
				}
				flusher.Flush()
				sent++
				lastSent = time.Now()
			}
		} else if fails++; fails > 30 {
			return // the computer went away; let the browser stop asking
		}
		select {
		case <-r.Context().Done():
			return
		case <-time.After(interval):
		}
	}
}

// ---- Routines ----

type routineView struct {
	*store.Routine
	When       string `json:"when"`
	WebhookURL string `json:"webhookUrl,omitempty"`
	Secret     string `json:"secret,omitempty"` // only right after creation
}

func viewRoutine(rt *store.Routine, r *http.Request) routineView {
	v := routineView{Routine: rt, When: cron.Describe(rt.Schedule)}
	if rt.Kind == "webhook" {
		scheme := "http"
		if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
			scheme = "https"
		}
		v.WebhookURL = scheme + "://" + r.Host + "/hooks/" + rt.ID
		v.When = "When the webhook is called"
	}
	return v
}

func (s *Server) listRoutines(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	list, err := s.Store.ListRoutines(r.Context(), b.ID, userFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "could not load routines")
		return
	}
	out := make([]routineView, 0, len(list))
	for _, rt := range list {
		out = append(out, viewRoutine(rt, r))
	}
	writeJSON(w, 200, map[string]any{"routines": out})
}

func (s *Server) createRoutine(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	var body struct {
		Name     string `json:"name"`
		Prompt   string `json:"prompt"`
		Kind     string `json:"kind"`
		Schedule string `json:"schedule"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	rt, secret, err := s.Routines.Create(r.Context(), b.ID, userFrom(r).ID, body.Name, body.Prompt, body.Kind, body.Schedule)
	if err != nil {
		if errors.Is(err, routines.ErrInvalid) {
			writeErr(w, 400, strings.TrimPrefix(err.Error(), routines.ErrInvalid.Error()+"\n"))
			return
		}
		writeErr(w, 500, "could not save the routine")
		return
	}
	v := viewRoutine(rt, r)
	v.Secret = secret
	writeJSON(w, 201, v)
}

func (s *Server) updateRoutine(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	var body struct {
		Active *bool `json:"active"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	var next *time.Time
	if body.Active != nil && *body.Active {
		if rt, err := s.Store.GetRoutine(r.Context(), b.ID, userFrom(r).ID, r.PathValue("rid")); err == nil && rt.Kind == "schedule" {
			if sc, err := cron.Parse(rt.Schedule); err == nil {
				if n := sc.Next(time.Now()); !n.IsZero() {
					next = &n
				}
			}
		}
	}
	rt, err := s.Store.UpdateRoutine(r.Context(), b.ID, userFrom(r).ID, r.PathValue("rid"), body.Active, next)
	if err != nil {
		writeErr(w, 404, "routine not found")
		return
	}
	s.Routines.Changed(b.ID, userFrom(r).ID)
	writeJSON(w, 200, viewRoutine(rt, r))
}

func (s *Server) deleteRoutine(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	if err := s.Store.DeleteRoutine(r.Context(), b.ID, userFrom(r).ID, r.PathValue("rid")); err != nil {
		writeErr(w, 404, "routine not found")
		return
	}
	s.Routines.Changed(b.ID, userFrom(r).ID)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) runRoutine(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	rt, err := s.Store.GetRoutine(r.Context(), b.ID, userFrom(r).ID, r.PathValue("rid"))
	if err != nil {
		writeErr(w, 404, "routine not found")
		return
	}
	s.Routines.Fire(r.Context(), b, rt, "")
	writeJSON(w, 202, map[string]bool{"started": true})
}

// webhook starts a webhook routine. Its body is outside text, so the run
// is marked untrusted and changes need approval.
func (s *Server) webhook(w http.ResponseWriter, r *http.Request) {
	if !s.hookLimit.Allow(s.clientIP(r)) {
		writeErr(w, 429, "slow down")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 256<<10))
	if err != nil {
		writeErr(w, 413, "body too large")
		return
	}
	rt, err := s.Store.RoutineByID(r.Context(), r.PathValue("rid"))
	bearer := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if err != nil || !rt.Active || !routines.Verify(rt, body, r.Header.Get("X-GBot-Signature"), r.Header.Get("X-GBot-Timestamp"), bearer, time.Now()) {
		writeErr(w, 401, "unauthorized")
		return
	}
	bot, err := s.Store.GetBotAnySpace(r.Context(), rt.BotID)
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	s.Routines.Fire(r.Context(), bot, rt, string(body))
	writeJSON(w, 202, map[string]bool{"accepted": true})
}

// ---- Integrations ----

type mcpView struct {
	*store.MCPServer
	Status string   `json:"status"`
	Tools  []string `json:"tools"`
	OAuth  bool     `json:"oauth"`
}

func (s *Server) mcpView(m *store.MCPServer) mcpView {
	v := mcpView{MCPServer: m, Status: s.Runtime.MCPStatus(m.ID), Tools: s.Runtime.MCPToolLabels(m.ID), OAuth: len(m.OAuth) > 0}
	if v.Tools == nil {
		v.Tools = []string{}
	}
	return v
}

func (s *Server) listMCP(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.ListMCP(r.Context(), userFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "could not load")
		return
	}
	out := make([]mcpView, 0, len(list))
	for _, m := range list {
		out = append(out, s.mcpView(m))
	}
	writeJSON(w, 200, map[string]any{"servers": out})
}

func (s *Server) createMCP(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name  string `json:"name"`
		URL   string `json:"url"`
		Token string `json:"token"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	body.Name = strings.TrimSpace(body.Name)
	u, err := url.Parse(strings.TrimSpace(body.URL))
	if body.Name == "" || utf8.RuneCountInString(body.Name) > 40 || err != nil || (u.Scheme != "https" && !(u.Scheme == "http" && isLocalHost(u.Hostname()))) || u.Host == "" {
		writeErr(w, 400, "give it a name and an https:// URL (http:// only for localhost)")
		return
	}
	var sealed []byte
	if t := strings.TrimSpace(body.Token); t != "" {
		if s.Sealer == nil {
			writeErr(w, 503, "secret storage is not configured")
			return
		}
		raw, _ := json.Marshal(map[string]string{"Authorization": "Bearer " + t})
		sealed = s.Sealer.Seal(raw)
	}
	m, err := s.Store.CreateMCP(r.Context(), userFrom(r).ID, body.Name, u.String(), sealed)
	if err != nil {
		writeErr(w, 500, "could not save")
		return
	}
	// Connect in the background: a slow or unreachable server must not
	// hold up the request, and the panel polls its status.
	s.Runtime.WarmMCP(m, s.Sealer)
	s.queueMCP()
	writeJSON(w, 201, s.mcpView(m))
}

func isLocalHost(h string) bool {
	return h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "host.docker.internal"
}

// startMCPSync runs one background worker for the lifetime of the
// server. Changes are coalesced: while a sync is running, a burst of
// edits results in one more pass over the final state.
func (s *Server) startMCPSync() {
	if s.Runtime == nil {
		return
	}
	s.mcpOnce.Do(func() {
		s.mcpWake = make(chan struct{}, 1)
		go func() {
			for range s.mcpWake {
				c, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
				s.Runtime.SyncMCP(c, s.Sealer)
				cancel()
			}
		}()
	})
}

func (s *Server) queueMCP() {
	if s.Runtime == nil {
		return
	}
	s.startMCPSync()
	select {
	case s.mcpWake <- struct{}{}:
	default: // a sync is already queued and will see this change too
	}
}

func (s *Server) toggleMCP(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Enabled bool `json:"enabled"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	m, err := s.Store.SetMCPEnabled(r.Context(), userFrom(r).ID, r.PathValue("id"), body.Enabled)
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	s.Runtime.ForgetMCP(m.ID)
	if body.Enabled {
		s.Runtime.WarmMCP(m, s.Sealer)
	}
	s.queueMCP()
	writeJSON(w, 200, s.mcpView(m))
}

func (s *Server) deleteMCP(w http.ResponseWriter, r *http.Request) {
	if err := s.Store.DeleteMCP(r.Context(), userFrom(r).ID, r.PathValue("id")); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	s.Runtime.ForgetMCP(r.PathValue("id"))
	// Rebuild the agent without the server, off the request path.
	s.queueMCP()
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) listSkills(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.ListSkills(r.Context(), userFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "could not load")
		return
	}
	if list == nil {
		list = []*store.Skill{}
	}
	writeJSON(w, 200, map[string]any{"skills": list})
}

func (s *Server) saveSkill(w http.ResponseWriter, r *http.Request) {
	var body store.Skill
	if !readJSON(w, r, &body) {
		return
	}
	body.Name = strings.ToLower(strings.TrimSpace(strings.ReplaceAll(body.Name, " ", "-")))
	if body.Name == "" || len(body.Name) > 40 || strings.ContainsAny(body.Name, "/\\") || strings.TrimSpace(body.Body) == "" ||
		len(body.Body) > 40000 || len(body.Description) > 300 {
		writeErr(w, 400, "a skill needs a short name, a one-line description and instructions")
		return
	}
	var k *store.Skill
	var err error
	if body.ID != "" {
		// Editing an existing skill: update its row, so a rename does not
		// leave the old name behind as a second skill.
		k, err = s.Store.UpdateSkill(r.Context(), userFrom(r).ID, body.ID, &body)
	} else {
		k, err = s.Store.UpsertSkill(r.Context(), userFrom(r).ID, &body)
	}
	var pg *pgconn.PgError
	switch {
	case errors.As(err, &pg) && pg.Code == "23505":
		writeErr(w, 409, "you already have a skill with that name")
		return
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, 404, "skill not found")
		return
	case err != nil:
		writeErr(w, 500, "could not save")
		return
	}
	writeJSON(w, 200, k)
}

func (s *Server) deleteSkill(w http.ResponseWriter, r *http.Request) {
	if err := s.Store.DeleteSkill(r.Context(), userFrom(r).ID, r.PathValue("id")); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// ---- Groups ----

func (s *Server) group(w http.ResponseWriter, r *http.Request) *store.Group {
	u := userFrom(r)
	g, err := s.Store.GroupFor(r.Context(), u.SpaceID, u.ID, r.PathValue("id"))
	if err != nil {
		writeErr(w, 404, "group not found")
		return nil
	}
	return g
}

func (s *Server) listGroups(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	list, err := s.Store.ListGroups(r.Context(), u.SpaceID, u.ID)
	if err != nil {
		writeErr(w, 500, "could not load groups")
		return
	}
	if list == nil {
		list = []*store.Group{}
	}
	writeJSON(w, 200, map[string]any{"groups": list})
}

func (s *Server) createGroup(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name   string   `json:"name"`
		BotIDs []string `json:"botIds"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	body.Name = strings.TrimSpace(body.Name)
	seen := map[string]bool{}
	for _, id := range body.BotIDs {
		seen[id] = true
	}
	if body.Name == "" || utf8.RuneCountInString(body.Name) > 40 || len(seen) != len(body.BotIDs) || len(body.BotIDs) < 2 || len(body.BotIDs) > store.MaxGroupBots {
		writeErr(w, 400, "name the group and pick 2 to 6 bots")
		return
	}
	u := userFrom(r)
	for _, id := range body.BotIDs {
		if _, err := s.Store.GetBotFor(r.Context(), u.SpaceID, u.ID, id); err != nil {
			writeErr(w, 400, "you can only put bots you can use in a group")
			return
		}
	}
	g, err := s.Store.CreateGroup(r.Context(), u.SpaceID, u.ID, body.Name, body.BotIDs)
	if err != nil {
		writeErr(w, 400, "those bots aren't all in this team")
		return
	}
	s.Hub.Publish(u.SpaceID, []string{u.ID}, runs.Event{Type: "group", GroupID: g.ID, Data: g})
	writeJSON(w, 201, g)
}

func (s *Server) archiveGroup(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	if err := s.Store.ArchiveGroup(r.Context(), u.SpaceID, u.ID, r.PathValue("id")); err != nil {
		writeErr(w, 404, "group not found")
		return
	}
	s.Runtime.Stop(runs.GroupKey(r.PathValue("id")))
	s.Hub.Publish(u.SpaceID, []string{u.ID}, runs.Event{Type: "group_removed", GroupID: r.PathValue("id")})
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) groupMessages(w http.ResponseWriter, r *http.Request) {
	g := s.group(w, r)
	if g == nil {
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
	msgs, err := s.Store.ListGroupMessages(r.Context(), g.ID, before, 100)
	if err != nil {
		writeErr(w, 500, "could not load messages")
		return
	}
	if msgs == nil {
		msgs = []*store.Message{}
	}
	writeJSON(w, 200, map[string]any{"messages": msgs})
}

// sendGroupMessage posts to a group. @mentioned bots answer; otherwise
// the first bot (the group's lead) does, and may hand off.
func (s *Server) sendGroupMessage(w http.ResponseWriter, r *http.Request) {
	g := s.group(w, r)
	if g == nil {
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
	m := &store.Message{GroupID: g.ID, Role: "user", Content: body.Text}
	if err := s.Store.AddMessage(r.Context(), m, &u.ID); err != nil {
		writeErr(w, 500, "could not send")
		return
	}
	s.Hub.Publish(u.SpaceID, []string{u.ID}, runs.Event{Type: "message", GroupID: g.ID, Data: m})
	var bots []*store.Bot
	for _, id := range g.BotIDs {
		b, err := s.Store.GetBotFor(r.Context(), u.SpaceID, u.ID, id)
		if err != nil {
			continue
		}
		if runs.Mentions(body.Text, b.Name) {
			bots = append(bots, b)
		}
	}
	if len(bots) == 0 && len(g.BotIDs) > 0 {
		if b, err := s.Store.GetBotFor(r.Context(), u.SpaceID, u.ID, g.BotIDs[0]); err == nil {
			bots = append(bots, b)
		}
	}
	for _, b := range bots {
		s.Runtime.Enqueue(runs.Job{Bot: b, Group: g, Trigger: "chat"})
	}
	writeJSON(w, 201, m)
}

func (s *Server) stopGroup(w http.ResponseWriter, r *http.Request) {
	if g := s.group(w, r); g != nil {
		writeJSON(w, 200, map[string]bool{"stopped": s.Runtime.Stop(runs.GroupKey(g.ID))})
	}
}
