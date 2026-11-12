// Package mcp implements the OAuth side of remote MCP servers: discovery,
// dynamic client registration, PKCE and token refresh. gbot registers
// itself with each provider, so adding an integration is one click and
// no provider-specific keys are needed.
package mcp

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const timeout = 20 * time.Second

// Config is what a server's authorization metadata tells us.
type Config struct {
	Resource              string   `json:"resource"`
	AuthorizationEndpoint string   `json:"authorization_endpoint"`
	TokenEndpoint         string   `json:"token_endpoint"`
	RegistrationEndpoint  string   `json:"registration_endpoint"`
	Scopes                []string `json:"scopes"`
}

// Client is the registration this gbot instance got from a provider.
type Client struct {
	ID     string `json:"client_id"`
	Secret string `json:"client_secret,omitempty"`
}

// Tokens are sealed in the database; the refresh grant needs the same
// client and endpoints that issued them.
type Tokens struct {
	Access        string   `json:"access_token"`
	Refresh       string   `json:"refresh_token,omitempty"`
	ExpiresAt     int64    `json:"expires_at,omitempty"`
	ClientID      string   `json:"client_id"`
	ClientSecret  string   `json:"client_secret,omitempty"`
	TokenEndpoint string   `json:"token_endpoint"`
	Resource      string   `json:"resource,omitempty"`
	Scopes        []string `json:"scopes,omitempty"`
}

// Expired leaves a minute of room so a call never starts with a token
// that dies mid-flight.
func (t *Tokens) Expired() bool {
	return t.ExpiresAt > 0 && time.Now().Add(time.Minute).Unix() >= t.ExpiresAt
}

func httpClient() *http.Client { return &http.Client{Timeout: timeout} }

func getJSON(ctx context.Context, c *http.Client, u string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%s", resp.Status)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(out)
}

// Discover reads the resource and authorization metadata. It falls back
// to the server's own origin for providers that publish the authorization
// metadata without a protected-resource document (Atlassian does).
func Discover(ctx context.Context, serverURL string) (*Config, error) {
	u, err := url.Parse(serverURL)
	if err != nil || u.Host == "" {
		return nil, errors.New("that doesn't look like a server URL")
	}
	c := httpClient()
	metaURL := probeMetadataURL(ctx, c, serverURL)
	if metaURL == "" {
		metaURL = wellKnown(u, "oauth-protected-resource")
	}
	var resource struct {
		Resource             string   `json:"resource"`
		AuthorizationServers []string `json:"authorization_servers"`
		ScopesSupported      []string `json:"scopes_supported"`
	}
	if err := getJSON(ctx, c, metaURL, &resource); err != nil && len(resource.AuthorizationServers) == 0 {
		// Not every provider publishes the resource document. The MCP
		// host itself is the usual authorization server.
		resource.AuthorizationServers = []string{u.Scheme + "://" + u.Host}
	}
	if resource.Resource == "" {
		resource.Resource = serverURL
	}
	var last error
	seen := map[string]bool{}
	for _, as := range resource.AuthorizationServers {
		if as == "" || seen[as] {
			continue
		}
		seen[as] = true
		cfg, err := authMetadata(ctx, c, as)
		if err == nil {
			cfg.Resource = resource.Resource
			if len(resource.ScopesSupported) > 0 {
				cfg.Scopes = resource.ScopesSupported
			}
			return cfg, nil
		}
		last = err
	}
	if last == nil {
		last = errors.New("no authorization server")
	}
	return nil, fmt.Errorf("this app didn't offer a sign-in: %w", last)
}

// probeMetadataURL asks the MCP server for the resource document in its
// WWW-Authenticate header, which is the exact location by spec.
func probeMetadataURL(ctx context.Context, c *http.Client, serverURL string) string {
	body := `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"gbot","version":"1"}}}`
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, serverURL, strings.NewReader(body))
	if err != nil {
		return ""
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	resp, err := c.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	return headerValue(resp.Header.Get("WWW-Authenticate"), "resource_metadata")
}

// headerValue pulls one quoted or bare parameter from a challenge.
func headerValue(h, key string) string {
	i := strings.Index(h, key+"=")
	if i < 0 {
		return ""
	}
	v := strings.TrimSpace(h[i+len(key)+1:])
	if strings.HasPrefix(v, `"`) {
		v = v[1:]
		if j := strings.Index(v, `"`); j >= 0 {
			return v[:j]
		}
		return v
	}
	if j := strings.IndexAny(v, ", "); j >= 0 {
		v = v[:j]
	}
	return v
}

// wellKnown inserts the well-known segment before the server path.
func wellKnown(u *url.URL, name string) string {
	p := strings.TrimSuffix(u.EscapedPath(), "/")
	return u.Scheme + "://" + u.Host + "/.well-known/" + name + p
}

// authMetadata tries both RFC 8414 placements: issuer plus the well-known
// path, and the well-known segment inserted before an issuer path.
func authMetadata(ctx context.Context, c *http.Client, issuer string) (*Config, error) {
	u, err := url.Parse(issuer)
	if err != nil || u.Host == "" {
		return nil, errors.New("bad authorization server")
	}
	candidates := []string{
		strings.TrimSuffix(issuer, "/") + "/.well-known/oauth-authorization-server",
		u.Scheme + "://" + u.Host + "/.well-known/oauth-authorization-server" + strings.TrimSuffix(u.EscapedPath(), "/"),
	}
	var last error
	for _, candidate := range candidates {
		var doc struct {
			AuthorizationEndpoint string   `json:"authorization_endpoint"`
			TokenEndpoint         string   `json:"token_endpoint"`
			RegistrationEndpoint  string   `json:"registration_endpoint"`
			ScopesSupported       []string `json:"scopes_supported"`
		}
		if err := getJSON(ctx, c, candidate, &doc); err != nil {
			last = err
			continue
		}
		if doc.AuthorizationEndpoint == "" || doc.TokenEndpoint == "" {
			continue
		}
		return &Config{
			AuthorizationEndpoint: doc.AuthorizationEndpoint,
			TokenEndpoint:         doc.TokenEndpoint,
			RegistrationEndpoint:  doc.RegistrationEndpoint,
			Scopes:                doc.ScopesSupported,
		}, nil
	}
	if last == nil {
		last = errors.New("no authorization metadata")
	}
	return nil, last
}

// Register creates a client for this gbot callback URL. Servers without
// dynamic registration (Slack, for one) can't use this flow.
func Register(ctx context.Context, cfg *Config, redirect string) (*Client, error) {
	if cfg.RegistrationEndpoint == "" {
		return nil, errors.New("this app needs a pre-registered client; add it with a token instead")
	}
	body, _ := json.Marshal(map[string]any{
		"client_name":                      "gbot",
		"redirect_uris":                    []string{redirect},
		"grant_types":                      []string{"authorization_code", "refresh_token"},
		"response_types":                   []string{"code"},
		"token_endpoint_auth_method":       "none",
		"code_challenge_methods_supported": []string{"S256"},
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, cfg.RegistrationEndpoint, strings.NewReader(string(body)))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	resp, err := httpClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return nil, fmt.Errorf("register: %s: %s", resp.Status, strings.TrimSpace(string(b)))
	}
	var out Client
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil {
		return nil, err
	}
	if out.ID == "" {
		return nil, errors.New("the app returned no client id")
	}
	return &out, nil
}

// AuthURL is where the person signs in. The resource parameter binds the
// token to this one server (RFC 8707).
func (c *Config) AuthURL(clientID, redirect, state, verifier string) string {
	q := url.Values{}
	q.Set("response_type", "code")
	q.Set("client_id", clientID)
	q.Set("redirect_uri", redirect)
	q.Set("state", state)
	q.Set("code_challenge", Challenge(verifier))
	q.Set("code_challenge_method", "S256")
	if c.Resource != "" {
		q.Set("resource", c.Resource)
	}
	if len(c.Scopes) > 0 {
		q.Set("scope", strings.Join(c.Scopes, " "))
	}
	return c.AuthorizationEndpoint + "?" + q.Encode()
}

func tokenRequest(ctx context.Context, endpoint string, form url.Values) (*Tokens, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	resp, err := httpClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken      string `json:"access_token"`
		RefreshToken     string `json:"refresh_token"`
		ExpiresIn        int64  `json:"expires_in"`
		Error            string `json:"error"`
		ErrorDescription string `json:"error_description"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil {
		return nil, fmt.Errorf("token: %s", resp.Status)
	}
	if out.Error != "" {
		if out.ErrorDescription != "" {
			return nil, errors.New(out.ErrorDescription)
		}
		return nil, errors.New(out.Error)
	}
	if out.AccessToken == "" {
		return nil, errors.New("the app returned no access token")
	}
	t := &Tokens{Access: out.AccessToken, Refresh: out.RefreshToken}
	if out.ExpiresIn > 0 {
		t.ExpiresAt = time.Now().Add(time.Duration(out.ExpiresIn) * time.Second).Unix()
	}
	return t, nil
}

// Exchange turns the callback code into tokens.
func Exchange(ctx context.Context, cfg *Config, clientID, clientSecret, code, verifier, redirect string) (*Tokens, error) {
	form := url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {code},
		"redirect_uri":  {redirect},
		"client_id":     {clientID},
		"code_verifier": {verifier},
	}
	if clientSecret != "" {
		form.Set("client_secret", clientSecret)
	}
	if cfg.Resource != "" {
		form.Set("resource", cfg.Resource)
	}
	t, err := tokenRequest(ctx, cfg.TokenEndpoint, form)
	if err != nil {
		return nil, err
	}
	t.ClientID, t.ClientSecret = clientID, clientSecret
	t.TokenEndpoint, t.Resource, t.Scopes = cfg.TokenEndpoint, cfg.Resource, cfg.Scopes
	return t, nil
}

// Refresh renews an access token. A missing refresh token means the
// person has to connect the app again.
func Refresh(ctx context.Context, t *Tokens) (*Tokens, error) {
	if t.Refresh == "" {
		return nil, errors.New("the sign-in expired; reconnect the app")
	}
	form := url.Values{
		"grant_type":    {"refresh_token"},
		"refresh_token": {t.Refresh},
		"client_id":     {t.ClientID},
	}
	if t.ClientSecret != "" {
		form.Set("client_secret", t.ClientSecret)
	}
	if t.Resource != "" {
		form.Set("resource", t.Resource)
	}
	next, err := tokenRequest(ctx, t.TokenEndpoint, form)
	if err != nil {
		return nil, err
	}
	next.ClientID, next.ClientSecret = t.ClientID, t.ClientSecret
	next.TokenEndpoint, next.Resource, next.Scopes = t.TokenEndpoint, t.Resource, t.Scopes
	if next.Refresh == "" {
		next.Refresh = t.Refresh
	}
	if next.ExpiresAt == 0 {
		next.ExpiresAt = t.ExpiresAt
	}
	return next, nil
}

// Challenge is the S256 PKCE challenge.
func Challenge(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// Random returns a URL-safe verifier or state.
func Random(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
