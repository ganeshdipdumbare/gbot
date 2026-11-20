package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"gbot/internal/crypto"
	"gbot/internal/replay"
	"gbot/internal/runs"
	"gbot/internal/slack"
	"gbot/internal/store"
)

// A Slack message becomes an ordinary run for the person who connected
// the workspace: their approvals, tools and memory apply, exactly as if
// they had typed it. The run is marked untrusted, because the text came
// from outside.
//
// The reply travels back the other way by watching the same event stream
// the web app watches, so nothing in the runtime has to know about Slack.

// slackTarget is where one pending reply belongs.
type slackTarget struct {
	installID string
	channel   string
	thread    string
	at        time.Time
}

// slackWaitTTL drops a destination whose run never produced a reply, so
// a late answer is not posted into an unrelated thread much later.
const slackWaitTTL = 30 * time.Minute

func slackKey(botID, userID string) string { return botID + "\x00" + userID }

func (s *Server) slackRemember(botID, userID string, t slackTarget) {
	s.slackMu.Lock()
	defer s.slackMu.Unlock()
	if s.slackWait == nil {
		s.slackWait = map[string]slackTarget{}
	}
	for k, v := range s.slackWait {
		if time.Since(v.at) > slackWaitTTL {
			delete(s.slackWait, k)
		}
	}
	s.slackWait[slackKey(botID, userID)] = t
}

func (s *Server) slackTake(botID, userID string) (slackTarget, bool) {
	s.slackMu.Lock()
	defer s.slackMu.Unlock()
	t, ok := s.slackWait[slackKey(botID, userID)]
	if ok {
		delete(s.slackWait, slackKey(botID, userID))
	}
	return t, ok && time.Since(t.at) <= slackWaitTTL
}

// slackRelay watches one space's events for finished replies and posts
// them back into the Slack thread that asked.
func (s *Server) ensureSlackRelay(spaceID, userID string) {
	key := slackKey(spaceID, userID)
	s.slackMu.Lock()
	if s.slackSubs == nil {
		s.slackSubs = map[string]bool{}
	}
	if s.slackSubs[key] {
		s.slackMu.Unlock()
		return
	}
	s.slackSubs[key] = true
	s.slackMu.Unlock()

	ch, _ := s.Hub.Subscribe(spaceID, userID)
	go func() {
		for raw := range ch {
			var ev struct {
				Type  string `json:"type"`
				BotID string `json:"botId"`
				Data  struct {
					Role    string `json:"role"`
					Content string `json:"content"`
				} `json:"data"`
			}
			if err := json.Unmarshal(raw, &ev); err != nil {
				continue
			}
			if ev.Type != "message_done" || ev.Data.Role != "bot" || strings.TrimSpace(ev.Data.Content) == "" {
				continue
			}
			target, ok := s.slackTake(ev.BotID, userID)
			if !ok {
				continue
			}
			s.slackPost(target, ev.Data.Content)
		}
	}()
}

func (s *Server) slackPost(t slackTarget, text string) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	in, err := s.Store.SlackInstallByID(ctx, t.installID)
	if err != nil || s.Sealer == nil {
		return
	}
	token, err := s.Sealer.Open(in.BotToken, crypto.Owner("slack-bot-token", in.OwnerID))
	if err != nil {
		s.Log.Warn("slack: cannot open bot token", "install", t.installID)
		return
	}
	c := &slack.Client{Token: string(token)}
	if err := c.Post(ctx, t.channel, t.thread, text); err != nil {
		s.Log.Warn("slack: post failed", "err", err, "channel", t.channel)
	}
}

// slackEvents is Slack's inbound webhook. It carries no session, so the
// signature is the only thing that makes it trustworthy.
func (s *Server) slackEvents(w http.ResponseWriter, r *http.Request) {
	if !s.hookLimit.Allow(s.clientIP(r)) {
		writeErr(w, 429, "slow down")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 256<<10))
	if err != nil {
		writeErr(w, 413, "body too large")
		return
	}
	var env slack.Envelope
	if err := json.Unmarshal(body, &env); err != nil {
		writeErr(w, 400, "bad payload")
		return
	}

	// The setup handshake names no workspace, so there is no install to
	// check it against yet. It carries nothing secret: Slack sends a
	// nonce and expects it back. Every real event below is verified.
	if env.Type == "url_verification" {
		writeJSON(w, 200, map[string]string{"challenge": env.Challenge})
		return
	}

	// Slack redelivers when a response is slow. The first delivery has
	// already been queued, so acting again would run the same request
	// twice.
	if r.Header.Get("X-Slack-Retry-Num") != "" {
		writeJSON(w, 200, map[string]bool{"ok": true})
		return
	}

	if s.Sealer == nil {
		writeErr(w, 503, "server key not configured")
		return
	}
	in, err := s.Store.SlackInstallByTeam(r.Context(), env.TeamID)
	if err != nil {
		writeErr(w, 401, "unauthorized")
		return
	}
	secret, err := s.Sealer.Open(in.SigningKey, crypto.Owner("slack-signing-key", in.OwnerID))
	if err != nil {
		writeErr(w, 401, "unauthorized")
		return
	}
	if !slack.Verify(secret, body, r.Header.Get("X-Slack-Request-Timestamp"), r.Header.Get("X-Slack-Signature"), time.Now()) {
		writeErr(w, 401, "unauthorized")
		return
	}
	// A signed request is accepted once. Slack retries deliver the same
	// signature again, and a captured one must not be replayed inside the
	// timestamp window.
	if !replay.Accept("slack|"+r.Header.Get("X-Slack-Signature"), time.Now(), 10*time.Minute) {
		// Already handled: acknowledge it, so Slack does not retry it again.
		writeJSON(w, 200, map[string]bool{"ok": true})
		return
	}

	// From here a 200 means "received"; an event gbot does not act on is
	// not an error, and saying so would make Slack retry it.
	ok := func() { writeJSON(w, 200, map[string]bool{"ok": true}) }

	if !env.Event.Actionable() {
		ok()
		return
	}
	link, err := s.Store.SlackLinkFor(r.Context(), in.ID, env.Event.Channel)
	if err != nil {
		ok()
		return
	}
	// The run is the install owner's, so the bot must be one they can use.
	bot, err := s.Store.GetBotFor(r.Context(), in.SpaceID, in.OwnerID, link.BotID)
	if err != nil {
		ok()
		return
	}

	prompt := env.Event.Prompt()
	m := &store.Message{BotID: bot.ID, Role: "user", Content: prompt, ChatUser: in.OwnerID}
	if err := s.Store.AddMessage(r.Context(), m, &in.OwnerID); err != nil {
		writeErr(w, 500, "could not accept")
		return
	}
	s.slackRemember(bot.ID, in.OwnerID, slackTarget{
		installID: in.ID, channel: env.Event.Channel, thread: env.Event.Thread(), at: time.Now(),
	})
	s.ensureSlackRelay(in.SpaceID, in.OwnerID)
	s.Hub.Publish(in.SpaceID, []string{in.OwnerID}, runs.Event{Type: "message", BotID: bot.ID, Data: m})
	s.Runtime.Enqueue(runs.Job{Bot: bot, For: in.OwnerID, Prompt: prompt, Trigger: "slack", Untrusted: true})
	ok()
}

// ---- Settings ----

type slackView struct {
	*store.SlackInstall
	Links []*store.SlackLink `json:"links"`
}

func (s *Server) listSlack(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	installs, err := s.Store.ListSlackInstalls(r.Context(), u.SpaceID)
	if err != nil {
		writeErr(w, 500, "could not load")
		return
	}
	out := []slackView{}
	for _, in := range installs {
		links, err := s.Store.ListSlackLinks(r.Context(), in.ID)
		if err != nil {
			writeErr(w, 500, "could not load")
			return
		}
		if links == nil {
			links = []*store.SlackLink{}
		}
		out = append(out, slackView{SlackInstall: in, Links: links})
	}
	writeJSON(w, 200, map[string]any{"installs": out, "eventUrl": s.slackEventURL()})
}

func (s *Server) slackEventURL() string {
	if s.PublicURL == "" {
		return ""
	}
	return strings.TrimRight(s.PublicURL, "/") + "/slack/events"
}

func (s *Server) connectSlack(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	if s.Sealer == nil {
		writeErr(w, 503, "server key not configured")
		return
	}
	var body struct {
		TeamID        string `json:"teamId"`
		TeamName      string `json:"teamName"`
		BotToken      string `json:"botToken"`
		SigningSecret string `json:"signingSecret"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	body.TeamID = strings.TrimSpace(body.TeamID)
	body.BotToken = strings.TrimSpace(body.BotToken)
	body.SigningSecret = strings.TrimSpace(body.SigningSecret)
	if body.TeamID == "" || body.BotToken == "" || body.SigningSecret == "" {
		writeErr(w, 400, "workspace ID, bot token and signing secret are all needed")
		return
	}
	in, err := s.Store.SaveSlackInstall(r.Context(), &store.SlackInstall{
		SpaceID: u.SpaceID, OwnerID: u.ID, TeamID: body.TeamID, TeamName: strings.TrimSpace(body.TeamName),
		BotToken:   s.Sealer.Seal([]byte(body.BotToken), crypto.Owner("slack-bot-token", u.ID)),
		SigningKey: s.Sealer.Seal([]byte(body.SigningSecret), crypto.Owner("slack-signing-key", u.ID)),
	})
	if err != nil {
		// The unique key is the workspace, so a clash means another
		// space already connected it.
		writeErr(w, 409, "that Slack workspace is already connected, by another team or another person")
		return
	}
	writeJSON(w, 201, slackView{SlackInstall: in, Links: []*store.SlackLink{}})
}

// slackInstallAs loads a workspace connection for a change. Its tokens are
// sealed to the person who connected it and its runs are theirs, so only
// they may change it; the team's owner may also disconnect it.
func (s *Server) slackInstallAs(w http.ResponseWriter, r *http.Request, ownerMayRemove bool) (*store.SlackInstall, bool) {
	u := userFrom(r)
	in, err := s.Store.SlackInstallInSpace(r.Context(), u.SpaceID, r.PathValue("id"))
	if err != nil {
		writeErr(w, 404, "not found")
		return nil, false
	}
	if in.OwnerID == u.ID {
		return in, true
	}
	if ownerMayRemove {
		if role, err := s.Store.SpaceRole(r.Context(), u.SpaceID, u.ID); err == nil && role == "owner" {
			return in, true
		}
	}
	writeErr(w, 403, "only the person who connected this workspace can change it")
	return nil, false
}

func (s *Server) disconnectSlack(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	if _, ok := s.slackInstallAs(w, r, true); !ok {
		return
	}
	if err := s.Store.DeleteSlackInstall(r.Context(), u.SpaceID, r.PathValue("id")); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	writeJSON(w, 200, map[string]bool{"disconnected": true})
}

// linkSlack points a channel at a bot, after checking the caller can see
// both the workspace and the bot.
func (s *Server) linkSlack(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	in, ok := s.slackInstallAs(w, r, false)
	if !ok {
		return
	}
	var body struct {
		ChannelID string `json:"channelId"`
		Channel   string `json:"channel"`
		BotID     string `json:"botId"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	body.ChannelID = strings.TrimSpace(body.ChannelID)
	if body.ChannelID == "" || body.BotID == "" {
		writeErr(w, 400, "a channel and a bot are both needed")
		return
	}
	if _, err := s.Store.GetBotFor(r.Context(), u.SpaceID, u.ID, body.BotID); err != nil {
		writeErr(w, 404, "no such bot")
		return
	}
	l := &store.SlackLink{InstallID: in.ID, ChannelID: body.ChannelID, Channel: strings.TrimPrefix(body.Channel, "#"), BotID: body.BotID}
	if err := s.Store.SetSlackLink(r.Context(), l); err != nil {
		writeErr(w, 500, "could not link")
		return
	}
	writeJSON(w, 200, l)
}

func (s *Server) unlinkSlack(w http.ResponseWriter, r *http.Request) {
	in, ok := s.slackInstallAs(w, r, true)
	if !ok {
		return
	}
	if err := s.Store.DeleteSlackLink(r.Context(), in.ID, r.PathValue("channel")); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	writeJSON(w, 200, map[string]bool{"unlinked": true})
}
