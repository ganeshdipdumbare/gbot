package api

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"gbot/internal/codex"
	"gbot/internal/models"
	"gbot/internal/runs"
	"gbot/internal/store"
)

type providerView struct {
	ID        string              `json:"id"`
	Name      string              `json:"name"`
	Kind      string              `json:"kind"`
	BaseURL   string              `json:"baseUrl"`
	HasKey    bool                `json:"hasKey"`
	Local     bool                `json:"local"`
	Models    []string            `json:"models"`
	Caps      map[string][]string `json:"caps"`
	Enabled   bool                `json:"enabled"`
	Status    string              `json:"status"`
	CreatedAt time.Time           `json:"createdAt"`
}

type providerBody struct {
	ID        *string   `json:"id"`
	Name      *string   `json:"name"`
	Kind      *string   `json:"kind"`
	BaseURL   *string   `json:"baseUrl"`
	APIKey    *string   `json:"apiKey"`
	RemoveKey bool      `json:"removeKey"`
	Local     *bool     `json:"local"`
	Models    *[]string `json:"models"`
	Enabled   *bool     `json:"enabled"`
}

func (s *Server) listProviders(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.ListProviders(r.Context(), userFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "could not load")
		return
	}
	out := make([]providerView, 0, len(list))
	for _, p := range list {
		out = append(out, s.providerView(p))
	}
	writeJSON(w, 200, map[string]any{"providers": out, "codex": map[string]bool{
		"available": codex.Available(), "allowed": codex.Allowed(userFrom(r).Email),
	}})
}

// codexDenied refuses the Codex kind to accounts the operator hasn't
// allowed: it spends the machine owner's ChatGPT subscription.
func codexDenied(w http.ResponseWriter, r *http.Request, kind string) bool {
	if kind != "codex" || codex.Allowed(userFrom(r).Email) {
		return false
	}
	writeErr(w, 403, "this account can't use the server's Codex login")
	return true
}

func (s *Server) createProvider(w http.ResponseWriter, r *http.Request) {
	var b providerBody
	if !readJSON(w, r, &b) {
		return
	}
	name := ""
	if b.Name != nil {
		name = strings.TrimSpace(*b.Name)
	}
	kind := "openai"
	if b.Kind != nil {
		kind = strings.TrimSpace(*b.Kind)
	}
	base := ""
	if b.BaseURL != nil {
		base = strings.TrimSpace(*b.BaseURL)
	}
	if msg := validProviderFields(name, kind, &base); msg != "" {
		writeErr(w, 400, msg)
		return
	}
	if codexDenied(w, r, kind) {
		return
	}
	manual := []string{}
	if b.Models != nil {
		manual = cleanModelList(*b.Models)
	}
	key := ""
	if b.APIKey != nil {
		key = strings.TrimSpace(*b.APIKey)
	}
	sealed, ok := s.sealKey(key)
	if !ok {
		writeErr(w, 503, "secret storage is not configured")
		return
	}
	local := models.IsLocalURL(base)
	if b.Local != nil {
		local = *b.Local
	}
	enabled := true
	if b.Enabled != nil {
		enabled = *b.Enabled
	}
	discovered, derr := models.Discover(r.Context(), kind, base, key)
	if derr != nil && len(manual) == 0 {
		writeErr(w, 400, "couldn't list models from that endpoint: "+derr.Error()+". Add model names manually.")
		return
	}
	p, err := s.Store.CreateProvider(r.Context(), &store.Provider{
		UserID: userFrom(r).ID, Name: name, Kind: kind, Prefix: newProviderPrefix(),
		BaseURL: base, Local: local, Discovered: discovered, Manual: manual, Enabled: enabled, Sealed: sealed,
	})
	if err != nil {
		s.Log.Error("create provider", "err", err)
		writeErr(w, 500, "could not save")
		return
	}
	s.syncProviders(r.Context())
	writeJSON(w, 201, s.providerView(p))
}

func (s *Server) updateProvider(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	cur, err := s.Store.GetProvider(r.Context(), u.ID, r.PathValue("id"))
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	var b providerBody
	if !readJSON(w, r, &b) {
		return
	}
	name, kind, base := cur.Name, cur.Kind, cur.BaseURL
	if b.Name != nil {
		name = strings.TrimSpace(*b.Name)
	}
	if b.Kind != nil {
		kind = strings.TrimSpace(*b.Kind)
	}
	if b.BaseURL != nil {
		base = strings.TrimSpace(*b.BaseURL)
	}
	if msg := validProviderFields(name, kind, &base); msg != "" {
		writeErr(w, 400, msg)
		return
	}
	if codexDenied(w, r, kind) {
		return
	}
	manual := cur.Manual
	if b.Models != nil {
		manual = cleanModelList(*b.Models)
	}
	local := cur.Local
	if b.Local != nil {
		local = *b.Local
	} else if b.BaseURL != nil || b.Kind != nil {
		local = models.IsLocalURL(base)
	}
	enabled := cur.Enabled
	if b.Enabled != nil {
		enabled = *b.Enabled
	}
	sealed := []byte(nil)
	key := s.openKey(cur)
	if b.RemoveKey {
		key = ""
	} else if b.APIKey != nil && strings.TrimSpace(*b.APIKey) != "" {
		key = strings.TrimSpace(*b.APIKey)
		var ok bool
		sealed, ok = s.sealKey(key)
		if !ok {
			writeErr(w, 503, "secret storage is not configured")
			return
		}
	}
	changed := kind != cur.Kind || base != cur.BaseURL || sealed != nil || b.RemoveKey
	discovered := cur.Discovered
	if changed {
		if list, err := models.Discover(r.Context(), kind, base, key); err == nil {
			discovered = list
		}
	}
	p, err := s.Store.UpdateProvider(r.Context(), u.ID, cur.ID, store.ProviderPatch{
		Name: &name, Kind: &kind, BaseURL: &base, Local: &local, Discovered: &discovered,
		Manual: &manual, Enabled: &enabled, Sealed: sealed, ClearKey: b.RemoveKey,
	})
	if err != nil {
		s.Log.Error("update provider", "err", err)
		writeErr(w, 500, "could not save")
		return
	}
	if s.Runtime != nil {
		s.Runtime.ForgetProvider(p.ID)
	}
	s.syncProviders(r.Context())
	writeJSON(w, 200, s.providerView(p))
}

func (s *Server) refreshProvider(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	cur, err := s.Store.GetProvider(r.Context(), u.ID, r.PathValue("id"))
	if err != nil {
		writeErr(w, 404, "not found")
		return
	}
	list, err := models.Discover(r.Context(), cur.Kind, cur.BaseURL, s.openKey(cur))
	if err != nil {
		writeErr(w, 400, "couldn't list models: "+err.Error())
		return
	}
	// Drop non-chat leftovers from earlier discovers so the picker stays usable.
	manual := make([]string, 0, len(cur.Manual))
	for _, m := range cur.Manual {
		if models.IsChat(m) {
			manual = append(manual, m)
		}
	}
	p, err := s.Store.UpdateProvider(r.Context(), u.ID, cur.ID, store.ProviderPatch{Discovered: &list, Manual: &manual})
	if err != nil {
		writeErr(w, 500, "could not save")
		return
	}
	writeJSON(w, 200, s.providerView(p))
}

func (s *Server) deleteProvider(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := s.Store.DeleteProvider(r.Context(), userFrom(r).ID, id); err != nil {
		writeErr(w, 404, "not found")
		return
	}
	if s.Runtime != nil {
		s.Runtime.ForgetProvider(id)
	}
	s.syncProviders(r.Context())
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// discoverProvider lists models for an endpoint without saving it, so the
// person can check a key before connecting.
func (s *Server) discoverProvider(w http.ResponseWriter, r *http.Request) {
	var b providerBody
	if !readJSON(w, r, &b) {
		return
	}
	kind := "openai"
	if b.Kind != nil {
		kind = strings.TrimSpace(*b.Kind)
	}
	base := ""
	if b.BaseURL != nil {
		base = strings.TrimSpace(*b.BaseURL)
	}
	key := ""
	if b.APIKey != nil {
		key = strings.TrimSpace(*b.APIKey)
	}
	// Editing an existing provider: fill in what the form left blank from
	// what is stored, including the sealed key, so Discover works.
	if b.ID != nil && *b.ID != "" {
		if cur, err := s.Store.GetProvider(r.Context(), userFrom(r).ID, *b.ID); err == nil {
			if b.Kind == nil {
				kind = cur.Kind
			}
			if b.BaseURL == nil {
				base = cur.BaseURL
			}
			if key == "" {
				key = s.openKey(cur)
			}
		}
	}
	if msg := validProviderFields("probe", kind, &base); msg != "" {
		writeErr(w, 400, msg)
		return
	}
	if codexDenied(w, r, kind) {
		return
	}
	list, err := models.Discover(r.Context(), kind, base, key)
	if err != nil {
		writeErr(w, 400, "couldn't list models: "+err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"models": list, "caps": models.ModelCaps(list)})
}

func (s *Server) providerView(p *store.Provider) providerView {
	status := "off"
	if p.Enabled && s.Runtime != nil {
		if st := s.Runtime.ProviderStatus(p.ID); st != "" {
			status = st
		}
	}
	return providerView{
		ID: p.ID, Name: p.Name, Kind: p.Kind, BaseURL: p.BaseURL, HasKey: p.HasKey,
		Local: p.Local, Models: p.Models(), Caps: models.ModelCaps(p.Models()), Enabled: p.Enabled, Status: status, CreatedAt: p.CreatedAt,
	}
}

func (s *Server) syncProviders(ctx context.Context) {
	if s.Runtime == nil {
		return
	}
	c, cancel := context.WithTimeout(context.WithoutCancel(ctx), 40*time.Second)
	defer cancel()
	s.Runtime.SyncProviders(c, s.Sealer)
}

// userProviders is the account's enabled providers in the catalog's shape.
func (s *Server) userProviders(ctx context.Context, userID string) []models.Provider {
	list, err := s.Store.ListProviders(ctx, userID)
	if err != nil {
		s.Log.Error("provider list", "err", err)
		return nil
	}
	return runs.ProviderModels(list)
}

func (s *Server) sealKey(key string) ([]byte, bool) {
	if key == "" {
		return nil, true
	}
	if s.Sealer == nil {
		return nil, false
	}
	return s.Sealer.Seal([]byte(key)), true
}

func (s *Server) openKey(p *store.Provider) string {
	if len(p.Sealed) == 0 || s.Sealer == nil {
		return ""
	}
	raw, err := s.Sealer.Open(p.Sealed)
	if err != nil {
		return ""
	}
	return string(raw)
}

func validProviderFields(name, kind string, base *string) string {
	if name == "" || utf8.RuneCountInString(name) > 40 {
		return "give it a name of up to 40 characters"
	}
	switch kind {
	case "openai", "anthropic", "gemini":
	case "codex":
		// Always Codex's own endpoint; the login decides what's reachable.
		*base = codex.BaseURL
		return ""
	default:
		return "kind must be openai, anthropic, gemini or codex"
	}
	*base = strings.TrimRight(strings.TrimSpace(*base), "/")
	if *base == "" {
		*base = models.DefaultBaseURL(kind)
	}
	if err := models.ValidProviderURL(*base); err != nil {
		return err.Error()
	}
	return ""
}

func cleanModelList(in []string) []string {
	seen := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, m := range in {
		m = strings.TrimSpace(m)
		if m == "" || len(m) > 200 || seen[m] {
			continue
		}
		seen[m] = true
		out = append(out, m)
		if len(out) >= 500 {
			break
		}
	}
	return out
}

func newProviderPrefix() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "mp" + hex.EncodeToString(b)
}
