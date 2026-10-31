package api

import (
	"errors"
	"net/http"
	"net/mail"
	"strings"

	"github.com/jackc/pgx/v5/pgconn"

	"gbot/internal/auth"
	"gbot/internal/models"
	"gbot/internal/store"
)

// setup tells the client whether this is a fresh install.
func (s *Server) setup(w http.ResponseWriter, r *http.Request) {
	n, err := s.Store.CountUsers(r.Context())
	if err != nil {
		writeErr(w, 500, "database unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"fresh": n == 0, "signup": n == 0 || s.AllowSignup})
}

type credentials struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Name     string `json:"name,omitempty"`
}

func (s *Server) signup(w http.ResponseWriter, r *http.Request) {
	if !s.authLimit.Allow(clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "too many attempts, try again in a minute")
		return
	}
	var c credentials
	if !readJSON(w, r, &c) {
		return
	}
	if n, _ := s.Store.CountUsers(r.Context()); n > 0 && !s.AllowSignup {
		writeErr(w, http.StatusForbidden, "sign-ups are closed on this server")
		return
	}
	c.Email = strings.ToLower(strings.TrimSpace(c.Email))
	c.Name = strings.TrimSpace(c.Name)
	if _, err := mail.ParseAddress(c.Email); err != nil || len(c.Email) > 254 {
		writeErr(w, 400, "enter a valid email")
		return
	}
	if len(c.Password) < 10 || len(c.Password) > 256 {
		writeErr(w, 400, "use a password of at least 10 characters")
		return
	}
	if c.Name == "" {
		c.Name = strings.Split(c.Email, "@")[0]
	}
	hash, err := auth.HashPassword(c.Password)
	if err != nil {
		writeErr(w, 500, "could not create account")
		return
	}
	first := store.NewBot{
		Name:  "Mochi",
		Title: "your first teammate",
		Look:  store.Look{"seed": auth.NewToken()[:10]},
	}
	u, err := s.Store.CreateUser(r.Context(), c.Email, c.Name, hash, s.Models.Pick(r.Context()), first)
	if err != nil {
		var pg *pgconn.PgError
		if errors.As(err, &pg) && pg.Code == "23505" {
			writeErr(w, 409, "that email already has an account")
			return
		}
		s.Log.Error("signup", "err", err)
		writeErr(w, 500, "could not create account")
		return
	}
	s.startSession(w, r, u)
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	if !s.authLimit.Allow(clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "too many attempts, try again in a minute")
		return
	}
	var c credentials
	if !readJSON(w, r, &c) {
		return
	}
	u, err := s.Store.UserByEmail(r.Context(), strings.ToLower(strings.TrimSpace(c.Email)))
	if err != nil {
		auth.CheckDummy(c.Password)
		writeErr(w, 401, "wrong email or password")
		return
	}
	if ok, _ := auth.CheckPassword(c.Password, u.PasswordHash()); !ok {
		writeErr(w, 401, "wrong email or password")
		return
	}
	s.startSession(w, r, u)
}

func (s *Server) startSession(w http.ResponseWriter, r *http.Request, u *store.User) {
	tok := auth.NewToken()
	if err := s.Store.CreateSession(r.Context(), tok, u.ID, sessionTTL); err != nil {
		writeErr(w, 500, "could not sign in")
		return
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: tok, Path: "/", HttpOnly: true,
		Secure: s.SecureCookies, SameSite: http.SameSiteLaxMode, MaxAge: int(sessionTTL.Seconds())})
	writeJSON(w, 200, u)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookie); err == nil {
		_ = s.Store.DeleteSession(r.Context(), c.Value)
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", HttpOnly: true,
		Secure: s.SecureCookies, SameSite: http.SameSiteLaxMode, MaxAge: -1})
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) me(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, userFrom(r)) }

func (s *Server) updateMe(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DefaultModel string `json:"defaultModel"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	u := userFrom(r)
	if body.DefaultModel != "" && s.Models.Pick(r.Context(), body.DefaultModel) != body.DefaultModel {
		writeErr(w, 400, "that model isn't available")
		return
	}
	if err := s.Store.SetDefaultModel(r.Context(), u.ID, body.DefaultModel); err != nil {
		writeErr(w, 500, "could not save")
		return
	}
	u.DefaultModel = body.DefaultModel
	writeJSON(w, 200, u)
}

func (s *Server) listModels(w http.ResponseWriter, r *http.Request) {
	s.Models.Invalidate()
	list := s.Models.List(r.Context())
	if list == nil {
		list = []models.Model{}
	}
	writeJSON(w, 200, map[string]any{"models": list, "ollamaHost": s.Models.OllamaHost})
}
