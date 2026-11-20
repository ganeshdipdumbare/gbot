package api

import (
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"unicode/utf8"

	"gbot/internal/runs"
	"gbot/internal/store"
)

// ---- Sharing a bot ----

// sharingView is what a manager sees on a bot's Share panel.
type sharingView struct {
	Visibility   string         `json:"visibility"`
	Members      []store.Member `json:"members"`
	SpaceMembers []store.Member `json:"spaceMembers"` // the team, to pick from
}

func (s *Server) sharing(w http.ResponseWriter, r *http.Request) {
	b := s.manage(w, r)
	if b == nil {
		return
	}
	members, err := s.Store.BotMembers(r.Context(), b.ID)
	if err != nil {
		writeErr(w, 500, "could not load sharing")
		return
	}
	team, err := s.Store.SpaceMembers(r.Context(), b.SpaceID)
	if err != nil {
		writeErr(w, 500, "could not load sharing")
		return
	}
	if members == nil {
		members = []store.Member{}
	}
	writeJSON(w, 200, sharingView{Visibility: b.Visibility, Members: members, SpaceMembers: team})
}

// notifyAccess tells people who gained or lost access to a bot, so their
// roster updates live. before is the audience prior to the change.
func (s *Server) notifyAccess(r *http.Request, b *store.Bot, before []string) {
	after := s.Runtime.Audience(b.ID)
	var lost, gained []string
	for _, id := range before {
		if !slices.Contains(after, id) {
			lost = append(lost, id)
		}
	}
	for _, id := range after {
		if !slices.Contains(before, id) {
			gained = append(gained, id)
		}
	}
	// The bot without a role: each person's role is theirs alone.
	shared := *b
	shared.Role = ""
	s.Hub.Publish(b.SpaceID, lost, runs.Event{Type: "bot_removed", BotID: b.ID})
	s.Hub.Publish(b.SpaceID, gained, runs.Event{Type: "bot", BotID: b.ID, Data: &shared})
}

func (s *Server) setVisibility(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Visibility string `json:"visibility"` // private | team | invited
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Visibility != "private" && body.Visibility != "team" && body.Visibility != "invited" {
		writeErr(w, 400, "visibility is private, team, or invited")
		return
	}
	b := s.manage(w, r)
	if b == nil {
		return
	}
	before := s.Runtime.Audience(b.ID)
	if err := s.Store.SetVisibility(r.Context(), b.ID, body.Visibility); err != nil {
		writeErr(w, 500, "could not save")
		return
	}
	b.Visibility = body.Visibility
	s.notifyAccess(r, b, before)
	s.sharing(w, r)
}

func (s *Server) addBotMember(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Email string `json:"email"`
		Role  string `json:"role"` // manager | collaborator
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Role != "manager" && body.Role != "collaborator" {
		writeErr(w, 400, "role is manager or collaborator")
		return
	}
	b := s.manage(w, r)
	if b == nil {
		return
	}
	u, err := s.Store.UserByEmail(r.Context(), strings.ToLower(strings.TrimSpace(body.Email)))
	if err != nil {
		writeErr(w, 404, "no account with that email; ask them to sign up first")
		return
	}
	if u.ID == b.OwnerID {
		writeErr(w, 400, "the owner already has full access")
		return
	}
	if _, err := s.Store.SpaceRole(r.Context(), b.SpaceID, u.ID); err != nil {
		writeErr(w, 400, "add them to the team first")
		return
	}
	// Managers can add collaborators; only the owner can make or unmake a manager.
	if body.Role == "manager" || s.currentRole(r, b.ID, u.ID) == "manager" {
		if b.Role != "owner" {
			writeErr(w, 403, "only the owner can change a manager")
			return
		}
	}
	before := s.Runtime.Audience(b.ID)
	if err := s.Store.SetBotMember(r.Context(), b.ID, u.ID, body.Role); err != nil {
		writeErr(w, 500, "could not save")
		return
	}
	s.notifyAccess(r, b, before)
	s.sharing(w, r)
}

// currentRole is a person's role on a bot, or "" if they have none.
func (s *Server) currentRole(r *http.Request, botID, userID string) string {
	members, err := s.Store.BotMembers(r.Context(), botID)
	if err != nil {
		return ""
	}
	for _, m := range members {
		if m.UserID == userID {
			return m.Role
		}
	}
	return ""
}

func (s *Server) removeBotMember(w http.ResponseWriter, r *http.Request) {
	b := s.manage(w, r)
	if b == nil {
		return
	}
	target := r.PathValue("uid")
	members, err := s.Store.BotMembers(r.Context(), b.ID)
	if err != nil {
		writeErr(w, 500, "could not save")
		return
	}
	var role string
	for _, m := range members {
		if m.UserID == target {
			role = m.Role
		}
	}
	if role == "" {
		writeErr(w, 404, "not shared with that person")
		return
	}
	// Only the owner can take away a manager's access.
	if role == "manager" && b.Role != "owner" {
		writeErr(w, 403, "only the owner can remove a manager")
		return
	}
	before := s.Runtime.Audience(b.ID)
	if err := s.Store.SetBotMember(r.Context(), b.ID, target, ""); err != nil {
		writeErr(w, 500, "could not save")
		return
	}
	// Their runs on this bot stop with their access.
	s.Runtime.Stop(runs.ChatKey(b.ID, target))
	s.notifyAccess(r, b, before)
	s.sharing(w, r)
}

// ---- Teams (spaces) ----

// spaceAs loads a team the caller belongs to. owner requires them to own it.
func (s *Server) spaceAs(w http.ResponseWriter, r *http.Request, owner bool) (string, bool) {
	id := r.PathValue("id")
	role, err := s.Store.SpaceRole(r.Context(), id, userFrom(r).ID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) || strings.Contains(err.Error(), "invalid input syntax") {
			writeErr(w, 404, "team not found")
		} else {
			writeErr(w, 500, "could not load the team")
		}
		return "", false
	}
	if owner && role != "owner" {
		writeErr(w, 403, "only the team's owner can do that")
		return "", false
	}
	return id, true
}

func (s *Server) listSpaces(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	list, err := s.Store.UserSpaces(r.Context(), u.ID)
	if err != nil {
		writeErr(w, 500, "could not load teams")
		return
	}
	writeJSON(w, 200, map[string]any{"spaces": list, "current": u.SpaceID})
}

// maxOwnedSpaces bounds the teams one person can create. It is generous;
// without a bound, team creation is free for anyone who signs up.
const maxOwnedSpaces = 50

func (s *Server) createSpace(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name string `json:"name"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	body.Name = strings.TrimSpace(body.Name)
	if body.Name == "" || utf8.RuneCountInString(body.Name) > 40 {
		writeErr(w, 400, "name the team (up to 40 characters)")
		return
	}
	if n, err := s.Store.CountOwnedSpaces(r.Context(), userFrom(r).ID); err != nil || n >= maxOwnedSpaces {
		writeErr(w, 400, fmt.Sprintf("you can own up to %d teams", maxOwnedSpaces))
		return
	}
	sp, err := s.Store.CreateSpace(r.Context(), userFrom(r).ID, body.Name)
	if err != nil {
		writeErr(w, 500, "could not create the team")
		return
	}
	writeJSON(w, 201, sp)
}

// selectSpace makes a team the one this session works in.
func (s *Server) selectSpace(w http.ResponseWriter, r *http.Request) {
	var body struct {
		SpaceID string `json:"spaceId"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if _, err := s.Store.SpaceRole(r.Context(), body.SpaceID, userFrom(r).ID); err != nil {
		writeErr(w, 404, "team not found")
		return
	}
	c, err := r.Cookie(sessionCookie)
	if err != nil || s.Store.SetSessionSpace(r.Context(), c.Value, body.SpaceID) != nil {
		writeErr(w, 500, "could not switch teams")
		return
	}
	writeJSON(w, 200, map[string]string{"spaceId": body.SpaceID})
}

func (s *Server) spaceMembers(w http.ResponseWriter, r *http.Request) {
	id, ok := s.spaceAs(w, r, false)
	if !ok {
		return
	}
	list, err := s.Store.SpaceMembers(r.Context(), id)
	if err != nil {
		writeErr(w, 500, "could not load members")
		return
	}
	writeJSON(w, 200, map[string]any{"members": list})
}

func (s *Server) addSpaceMember(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Email string `json:"email"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	id, ok := s.spaceAs(w, r, true)
	if !ok {
		return
	}
	u, err := s.Store.UserByEmail(r.Context(), strings.ToLower(strings.TrimSpace(body.Email)))
	if err != nil {
		writeErr(w, 404, "no account with that email; ask them to sign up first")
		return
	}
	if err := s.Store.AddSpaceMember(r.Context(), id, u.ID); err != nil {
		writeErr(w, 500, "could not add them")
		return
	}
	s.spaceMembers(w, r)
}

func (s *Server) removeSpaceMember(w http.ResponseWriter, r *http.Request) {
	// Anyone may leave a team they were added to; only the owner removes
	// others. An owner's own row is never removed (the store refuses).
	self := r.PathValue("uid") == userFrom(r).ID
	id, ok := s.spaceAs(w, r, !self)
	if !ok {
		return
	}
	if err := s.Store.RemoveSpaceMember(r.Context(), id, r.PathValue("uid")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeErr(w, 404, "not a member")
			return
		}
		writeErr(w, 500, "could not remove them")
		return
	}
	s.spaceMembers(w, r)
}
