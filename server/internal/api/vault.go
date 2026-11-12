package api

import (
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"gbot/internal/store"
)

// The vault keeps account passwords sealed. The API never returns a value
// and the model never receives one; the bot can only type a named secret
// into a password field on one of the secret's origins.

type vaultView struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Origins   []string  `json:"origins"`
	UpdatedAt time.Time `json:"updatedAt"`
}

func viewVaultSecret(v *store.VaultSecret) vaultView {
	origins := v.Origins
	if origins == nil {
		origins = []string{}
	}
	return vaultView{ID: v.ID, Name: v.Name, Origins: origins, UpdatedAt: v.UpdatedAt}
}

func (s *Server) listVault(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.ListVault(r.Context(), userFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "could not load the vault")
		return
	}
	out := make([]vaultView, 0, len(list))
	for _, v := range list {
		out = append(out, viewVaultSecret(v))
	}
	writeJSON(w, 200, map[string]any{"secrets": out})
}

func validVaultSecret(name, value string, origins []string, needValue bool) ([]string, string) {
	name = strings.TrimSpace(name)
	if name == "" || utf8.RuneCountInString(name) > 60 {
		return nil, "give the secret a short name"
	}
	if needValue && (len(value) < 1 || len(value) > 8192) {
		return nil, "the value must be 1 to 8192 characters"
	}
	if len(origins) == 0 || len(origins) > 20 {
		return nil, "add 1 to 20 sites where the bot may use it"
	}
	hosts, msg := cleanHosts(origins)
	if msg != "" {
		return nil, msg
	}
	if len(hosts) == 0 {
		return nil, "add 1 to 20 sites where the bot may use it"
	}
	return hosts, ""
}

func (s *Server) createVault(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name    string   `json:"name"`
		Value   string   `json:"value"`
		Origins []string `json:"origins"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	hosts, msg := validVaultSecret(body.Name, body.Value, body.Origins, true)
	if msg != "" {
		writeErr(w, 400, msg)
		return
	}
	if s.Sealer == nil {
		writeErr(w, 503, "secret storage is not configured")
		return
	}
	v, err := s.Store.CreateVaultSecret(r.Context(), &store.VaultSecret{
		UserID: userFrom(r).ID, Name: strings.TrimSpace(body.Name), Origins: hosts,
		Sealed: s.Sealer.Seal([]byte(body.Value)),
	})
	if err != nil {
		if strings.Contains(err.Error(), "unique") {
			writeErr(w, 409, "a secret with that name already exists")
			return
		}
		writeErr(w, 500, "could not save the secret")
		return
	}
	writeJSON(w, 201, viewVaultSecret(v))
}

func (s *Server) updateVault(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	cur, err := s.Store.VaultSecretFor(r.Context(), u.ID, r.PathValue("id"))
	if err != nil {
		writeErr(w, 404, "secret not found")
		return
	}
	var body struct {
		Name    *string   `json:"name"`
		Value   *string   `json:"value"`
		Origins *[]string `json:"origins"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	name := cur.Name
	if body.Name != nil {
		name = *body.Name
	}
	origins := cur.Origins
	if body.Origins != nil {
		origins = *body.Origins
	}
	value := ""
	needValue := false
	if body.Value != nil && *body.Value != "" {
		value, needValue = *body.Value, true
	}
	hosts, msg := validVaultSecret(name, value, origins, needValue)
	if msg != "" {
		writeErr(w, 400, msg)
		return
	}
	var sealed []byte
	if needValue {
		if s.Sealer == nil {
			writeErr(w, 503, "secret storage is not configured")
			return
		}
		sealed = s.Sealer.Seal([]byte(value))
	}
	v, err := s.Store.UpdateVaultSecret(r.Context(), u.ID, cur.ID, &name, &hosts, sealed)
	if err != nil {
		if strings.Contains(err.Error(), "unique") {
			writeErr(w, 409, "a secret with that name already exists")
			return
		}
		writeErr(w, 500, "could not save the secret")
		return
	}
	writeJSON(w, 200, viewVaultSecret(v))
}

func (s *Server) deleteVault(w http.ResponseWriter, r *http.Request) {
	if err := s.Store.DeleteVaultSecret(r.Context(), userFrom(r).ID, r.PathValue("id")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeErr(w, 404, "secret not found")
			return
		}
		writeErr(w, 500, "could not delete the secret")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
