package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"gbot/internal/computer"
	"gbot/internal/cron"
	"gbot/internal/routines"
	"gbot/internal/runs"
	"gbot/internal/store"
)

func (s *Server) routesV2(mux *http.ServeMux) {
	mux.Handle("GET /api/settings/rules", s.authed(s.listRules))
	mux.Handle("PUT /api/settings/rules", s.authed(s.setRule))
	mux.Handle("POST /api/cards/{id}", s.authed(s.answerCard))

	mux.Handle("GET /api/bots/{id}/computer", s.authed(s.computerStatus))
	mux.Handle("POST /api/bots/{id}/computer/{action}", s.authed(s.computerAction))
	mux.Handle("GET /api/bots/{id}/computer/screen", s.authed(s.computerScreen))

	mux.Handle("GET /api/bots/{id}/routines", s.authed(s.listRoutines))
	mux.Handle("POST /api/bots/{id}/routines", s.authed(s.createRoutine))
	mux.Handle("PATCH /api/bots/{id}/routines/{rid}", s.authed(s.updateRoutine))
	mux.Handle("DELETE /api/bots/{id}/routines/{rid}", s.authed(s.deleteRoutine))
	mux.Handle("POST /api/bots/{id}/routines/{rid}/run", s.authed(s.runRoutine))
	mux.HandleFunc("POST /hooks/{rid}", s.webhook)

	mux.Handle("GET /api/mcp", s.authed(s.listMCP))
	mux.Handle("POST /api/mcp", s.authed(s.createMCP))
	mux.Handle("PATCH /api/mcp/{id}", s.authed(s.toggleMCP))
	mux.Handle("DELETE /api/mcp/{id}", s.authed(s.deleteMCP))
	mux.Handle("GET /api/skills", s.authed(s.listSkills))
	mux.Handle("POST /api/skills", s.authed(s.saveSkill))
	mux.Handle("DELETE /api/skills/{id}", s.authed(s.deleteSkill))

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
	// The card must belong to a chat in the caller's space.
	var group *store.Group
	if card.GroupID != "" {
		if group, err = s.Store.GetGroup(r.Context(), u.SpaceID, card.GroupID); err != nil {
			writeErr(w, 404, "card not found")
			return
		}
	}
	bot, err := s.Store.GetBot(r.Context(), u.SpaceID, card.BotID)
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
		if errors.Is(err, runs.ErrCardDone) {
			code = 409
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

func (s *Server) computerStatus(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil || !s.needComputer(w) {
		return
	}
	writeJSON(w, 200, s.Computer.Status(r.Context(), b.ID))
}

func (s *Server) computerAction(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil || !s.needComputer(w) {
		return
	}
	var err error
	switch r.PathValue("action") {
	case "start":
		err = s.Computer.Ensure(r.Context(), b.ID, b.Network)
	case "stop":
		s.Computer.Stop(r.Context(), b.ID)
	case "control":
		var body struct {
			User bool `json:"user"`
		}
		if !readJSON(w, r, &body) {
			return
		}
		if body.User {
			if err = s.Computer.Ensure(r.Context(), b.ID, b.Network); err == nil {
				err = s.Computer.TakeControl(b.ID, true)
			}
		} else {
			err = s.Computer.TakeControl(b.ID, false)
		}
	case "input":
		var in computer.Input
		if !readJSON(w, r, &in) {
			return
		}
		err = s.Computer.Send(r.Context(), b.ID, in)
	case "show":
		var body struct {
			App string `json:"app"`
		}
		if !readJSON(w, r, &body) {
			return
		}
		if err = s.Computer.Ensure(r.Context(), b.ID, b.Network); err == nil {
			err = s.Computer.Show(r.Context(), b.ID, body.App)
		}
	default:
		writeErr(w, 404, "not found")
		return
	}
	if err != nil {
		writeErr(w, 409, err.Error())
		return
	}
	writeJSON(w, 200, s.Computer.Status(r.Context(), b.ID))
}

func (s *Server) computerScreen(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil || !s.needComputer(w) {
		return
	}
	img, err := s.Computer.Screenshot(r.Context(), b.ID)
	if err != nil {
		writeErr(w, 409, err.Error())
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(img)
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
	list, err := s.Store.ListRoutines(r.Context(), b.ID)
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
	rt, secret, err := s.Routines.Create(r.Context(), b.ID, body.Name, body.Prompt, body.Kind, body.Schedule)
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
		if rt, err := s.Store.GetRoutine(r.Context(), b.ID, r.PathValue("rid")); err == nil && rt.Kind == "schedule" {
			if sc, err := cron.Parse(rt.Schedule); err == nil {
				n := sc.Next(time.Now())
				next = &n
			}
		}
	}
	rt, err := s.Store.UpdateRoutine(r.Context(), b.ID, r.PathValue("rid"), body.Active, next)
	if err != nil {
		writeErr(w, 404, "routine not found")
		return
	}
	writeJSON(w, 200, viewRoutine(rt, r))
}

func (s *Server) deleteRoutine(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	if err := s.Store.DeleteRoutine(r.Context(), b.ID, r.PathValue("rid")); err != nil {
		writeErr(w, 404, "routine not found")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) runRoutine(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	rt, err := s.Store.GetRoutine(r.Context(), b.ID, r.PathValue("rid"))
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
	if !s.hookLimit.Allow(clientIP(r)) {
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
	if err != nil || !rt.Active || !routines.Verify(rt, body, r.Header.Get("X-GBot-Signature"), bearer) {
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
}

func (s *Server) mcpView(m *store.MCPServer) mcpView {
	v := mcpView{MCPServer: m, Status: s.Runtime.MCPStatus(m.ID), Tools: s.Runtime.MCPTools(m.ID)}
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
	s.syncMCP(r.Context())
	writeJSON(w, 201, s.mcpView(m))
}

func isLocalHost(h string) bool {
	return h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "host.docker.internal"
}

func (s *Server) syncMCP(ctx context.Context) {
	c, cancel := context.WithTimeout(context.WithoutCancel(ctx), 40*time.Second)
	defer cancel()
	s.Runtime.SyncMCP(c, s.Sealer)
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
	s.syncMCP(r.Context())
	writeJSON(w, 200, s.mcpView(m))
}

func (s *Server) deleteMCP(w http.ResponseWriter, r *http.Request) {
	if err := s.Store.DeleteMCP(r.Context(), userFrom(r).ID, r.PathValue("id")); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	s.Runtime.ForgetMCP(r.PathValue("id"))
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
	k, err := s.Store.UpsertSkill(r.Context(), userFrom(r).ID, &body)
	if err != nil {
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
	g, err := s.Store.GetGroup(r.Context(), userFrom(r).SpaceID, r.PathValue("id"))
	if err != nil {
		writeErr(w, 404, "group not found")
		return nil
	}
	return g
}

func (s *Server) listGroups(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.ListGroups(r.Context(), userFrom(r).SpaceID)
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
	g, err := s.Store.CreateGroup(r.Context(), u.SpaceID, body.Name, body.BotIDs)
	if err != nil {
		writeErr(w, 400, "those bots aren't all yours")
		return
	}
	s.Hub.Publish(u.SpaceID, runs.Event{Type: "group", GroupID: g.ID, Data: g})
	writeJSON(w, 201, g)
}

func (s *Server) archiveGroup(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	if err := s.Store.ArchiveGroup(r.Context(), u.SpaceID, r.PathValue("id")); err != nil {
		writeErr(w, 404, "group not found")
		return
	}
	s.Runtime.Stop("g:" + r.PathValue("id"))
	s.Hub.Publish(u.SpaceID, runs.Event{Type: "group_removed", GroupID: r.PathValue("id")})
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
	s.Hub.Publish(u.SpaceID, runs.Event{Type: "message", GroupID: g.ID, Data: m})
	var bots []*store.Bot
	for _, id := range g.BotIDs {
		b, err := s.Store.GetBot(r.Context(), u.SpaceID, id)
		if err != nil {
			continue
		}
		if runs.Mentions(body.Text, b.Name) {
			bots = append(bots, b)
		}
	}
	if len(bots) == 0 && len(g.BotIDs) > 0 {
		if b, err := s.Store.GetBot(r.Context(), u.SpaceID, g.BotIDs[0]); err == nil {
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
		writeJSON(w, 200, map[string]bool{"stopped": s.Runtime.Stop("g:" + g.ID)})
	}
}
