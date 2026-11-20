package runs

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/ganeshdipdumbare/kritrim"

	"gbot/internal/crypto"
	mcpoauth "gbot/internal/mcp"
	"gbot/internal/netguard"
	"gbot/internal/store"
)

// MCP servers are remote tools the person adds. Each server's tools are
// prefixed with its own short name, and the guard shows them only to runs
// of the account that added the server.
//
// gbot connects each server itself to check it and learn its tools, then
// registers those tools on every agent it builds. Doing the calls here
// (instead of handing the URL to the SDK) keeps large SSE responses and
// session handling in one place. A change to the set closes the cached
// agents, so the next run reconnects without a removed integration.

// mcpServer is one enabled server, with its headers decrypted.
type mcpServer struct {
	id      string
	owner   string
	name    string
	prefix  string
	url     string
	headers map[string]any
	sig     string // url and headers; a change means reconnect
}

// mcpToolDef is one tool as the server described it.
type mcpToolDef struct {
	Name        string         `json:"name"`
	Title       string         `json:"title,omitempty"`
	Description string         `json:"description"`
	Schema      map[string]any `json:"inputSchema"`
	Annotations map[string]any `json:"annotations,omitempty"`
}

// label is the name the integrations panel shows: the server's own title
// when it sent one, otherwise its bare tool name (no invented wording).
func (d mcpToolDef) label() string {
	if t := strings.TrimSpace(d.Title); t != "" {
		return t
	}
	if d.Annotations != nil {
		if t, ok := d.Annotations["title"].(string); ok {
			if t = strings.TrimSpace(t); t != "" {
				return t
			}
		}
	}
	name := d.Name
	if i := strings.Index(name, "__"); i >= 0 {
		name = name[i+2:]
	}
	return name
}

type mcpState struct {
	srv    mcpServer
	status string       // connected | error message
	loaded bool         // the live agents serve this server
	tools  []string     // tool names the server offers
	defs   []mcpToolDef // the same tools with schemas, for the agents
	// off marks a server that was just disabled or deleted. It stays in
	// the map (still counted as live) until the next sync rebuilds the
	// agents without it, so the comparison can see what must be removed.
	off bool
}

func mcpPrefix(id string) string { return "m" + strings.ReplaceAll(id, "-", "")[:8] }

// decodeMCP reads a stored server, opening its sealed headers and tokens.
func decodeMCP(s *store.MCPServer, sealer *crypto.Sealer) mcpServer {
	headers := map[string]any{}
	if len(s.Sealed) > 0 && sealer != nil {
		if raw, err := sealer.Open(s.Sealed, crypto.Owner("mcp-headers", s.UserID)); err == nil {
			var h map[string]string
			_ = json.Unmarshal(raw, &h)
			for k, v := range h {
				headers[k] = v
			}
		}
	}
	if t := openOAuth(s, sealer); t != nil {
		headers["Authorization"] = "Bearer " + t.Access
	}
	sig, _ := json.Marshal(headers)
	return mcpServer{id: s.ID, owner: s.UserID, name: s.Name, prefix: mcpPrefix(s.ID), url: s.URL,
		headers: headers, sig: s.URL + "\n" + string(sig)}
}

// openOAuth opens the sealed tokens. A broken seal is treated as no
// token, so the server's error (401) is what the person sees.
func openOAuth(s *store.MCPServer, sealer *crypto.Sealer) *mcpoauth.Tokens {
	if len(s.OAuth) == 0 || sealer == nil {
		return nil
	}
	raw, err := sealer.Open(s.OAuth, crypto.Owner("mcp-oauth", s.UserID))
	if err != nil {
		return nil
	}
	var t mcpoauth.Tokens
	if json.Unmarshal(raw, &t) != nil || t.Access == "" {
		return nil
	}
	return &t
}

// refreshToken renews an expiring access token before it is handed to
// the agents. A failed refresh leaves the old token; the probe then
// reports the provider's error and the person can reconnect.
func (r *Runtime) refreshToken(ctx context.Context, s *store.MCPServer, sealer *crypto.Sealer) *store.MCPServer {
	t := openOAuth(s, sealer)
	if t == nil || !t.Expired() {
		return s
	}
	next, err := mcpoauth.Refresh(ctx, t)
	if err != nil {
		r.log.Warn("mcp refresh", "server", s.Name, "err", err)
		return s
	}
	raw, _ := json.Marshal(next)
	sealed := sealer.Seal(raw, crypto.Owner("mcp-oauth", s.UserID))
	updated, err := r.store.SetMCPOAuth(ctx, s.UserID, s.ID, sealed)
	if err != nil {
		r.log.Warn("mcp refresh save", "server", s.Name, "err", err)
		return s
	}
	r.addSecret(next.Access)
	if next.Refresh != "" {
		r.addSecret(next.Refresh)
	}
	return updated
}

// SyncMCP makes the live agents serve exactly the enabled servers.
func (r *Runtime) SyncMCP(ctx context.Context, sealer *crypto.Sealer) {
	r.syncMu.Lock()
	defer r.syncMu.Unlock()
	servers, err := r.store.AllEnabledMCP(ctx)
	if err != nil {
		r.log.Error("mcp list", "err", err)
		return
	}

	r.mu.Lock()
	prev := make(map[string]mcpState, len(r.mcp))
	for id, st := range r.mcp {
		prev[id] = st
	}
	r.mu.Unlock()

	next := map[string]mcpState{}
	for _, s := range servers {
		s = r.refreshToken(ctx, s, sealer)
		if t := openOAuth(s, sealer); t != nil {
			r.addSecret(t.Access)
			if t.Refresh != "" {
				r.addSecret(t.Refresh)
			}
		}
		srv := decodeMCP(s, sealer)
		for _, v := range srv.headers {
			if str, ok := v.(string); ok {
				r.addSecret(str)
			}
		}
		if st, ok := prev[srv.id]; ok && st.loaded && st.srv.sig == srv.sig {
			next[srv.id] = st // already live, unchanged
			continue
		}
		defs, err := r.probe(srv)
		if err != nil {
			status := r.redact(err.Error())
			r.log.Warn("mcp load", "server", srv.name, "err", status)
			next[srv.id] = mcpState{srv: srv, status: status}
			continue
		}
		names := make([]string, len(defs))
		for i, d := range defs {
			names[i] = d.Name
		}
		next[srv.id] = mcpState{srv: srv, status: "connected", loaded: true, tools: names, defs: defs}
	}

	// Swap the live set before dropping agents, so a run that starts in
	// between builds with the new tools. The generation check in agentFor
	// discards one that read the old set.
	changed := !sameLive(prev, next)
	r.mu.Lock()
	r.mcp = next
	r.mu.Unlock()
	if changed {
		r.invalidateAgents()
	}
}

// sameLive reports whether two syncs leave the same servers live, with
// the same connection settings.
func sameLive(prev, next map[string]mcpState) bool {
	a, b := liveSigs(prev), liveSigs(next)
	if len(a) != len(b) {
		return false
	}
	for id, sig := range a {
		if other, ok := b[id]; !ok || other != sig {
			return false
		}
	}
	return true
}

func liveSigs(m map[string]mcpState) map[string]string {
	out := map[string]string{}
	for id, st := range m {
		if st.loaded {
			out[id] = st.srv.sig
		}
	}
	return out
}

// mcpTools is the live tool set for one agent. The SDK's own MCP client
// uses a 64 KB line buffer, which real servers (Notion) exceed, so the
// calls go through gbot's client instead.
func (r *Runtime) mcpTools() []kritrim.Tool {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []kritrim.Tool
	for _, st := range r.mcp {
		if !st.loaded {
			continue
		}
		srv := st.srv
		for _, d := range st.defs {
			name := d.Name
			short := strings.TrimPrefix(name, srv.prefix+"__")
			schema := d.Schema
			if schema == nil {
				schema = map[string]any{"type": "object"}
			}
			out = append(out, kritrim.Risky(kritrim.NewRawTool(name, d.Description, schema,
				func(ctx context.Context, args map[string]any) (string, error) {
					return r.callTool(ctx, srv, short, args)
				})))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// callTool initializes a session and calls one remote tool. Each call
// gets its own session so parallel tool calls can't interleave ids.
func (r *Runtime) callTool(ctx context.Context, s mcpServer, name string, args map[string]any) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	headers := make(map[string]string, len(s.headers))
	for k, v := range s.headers {
		if str, ok := v.(string); ok {
			headers[k] = str
		}
	}
	p := &mcpProbe{url: s.url, headers: headers, client: netguard.Client(2 * time.Minute)}
	if err := p.call(ctx, "initialize", map[string]any{
		"protocolVersion": "2025-06-18",
		"capabilities":    map[string]any{},
		"clientInfo":      map[string]any{"name": "gbot", "version": "1"},
	}, &map[string]any{}); err != nil {
		return "", err
	}
	_ = p.call(ctx, "notifications/initialized", nil, nil)
	var res struct {
		Content    []mcpContent    `json:"content"`
		Structured json.RawMessage `json:"structuredContent"`
		IsError    bool            `json:"isError"`
	}
	if err := p.call(ctx, "tools/call", map[string]any{"name": name, "arguments": args}, &res); err != nil {
		return "", err
	}
	out := r.readContent(ctx, s.name, res.Content)
	if out == "" && len(res.Structured) > 0 {
		out = string(res.Structured)
	}
	if out == "" {
		raw, _ := json.Marshal(res.Content)
		out = string(raw)
	}
	if res.IsError {
		return "", errors.New(r.redact(out))
	}
	return out, nil
}

// mcpContent is one block of a tool's answer. A server may hand back
// text, a picture, a sound, or a resource that carries either.
type mcpContent struct {
	Type     string `json:"type"`
	Text     string `json:"text"`
	Data     string `json:"data"`     // base64, for image and audio
	MimeType string `json:"mimeType"` // of Data
	URI      string `json:"uri"`      // resource_link
	Name     string `json:"name"`
	Resource *struct {
		URI      string `json:"uri"`
		MimeType string `json:"mimeType"`
		Text     string `json:"text"`
		Blob     string `json:"blob"` // base64
	} `json:"resource"`
}

// readContent turns a tool's answer into the text the model reads, and
// keeps any picture for the chat. A model only ever gets text back from a
// tool, so a picture that is only described to it would be lost: it is
// saved and shown to the person instead, and the model is told it is
// there, in the same place in the answer.
func (r *Runtime) readContent(ctx context.Context, server string, blocks []mcpContent) string {
	var b strings.Builder
	line := func(s string) {
		if s == "" {
			return
		}
		if b.Len() > 0 {
			b.WriteString("\n")
		}
		b.WriteString(s)
	}
	for _, c := range blocks {
		data, mime, text := c.Data, c.MimeType, c.Text
		if c.Resource != nil {
			if c.Resource.Text != "" {
				text = c.Resource.Text
			}
			if c.Resource.Blob != "" {
				data, mime = c.Resource.Blob, c.Resource.MimeType
			}
			if text == "" && data == "" && c.Resource.URI != "" {
				text = "Resource: " + c.Resource.URI
			}
		}
		switch {
		case text != "":
			line(text)
		case data != "" && showableImage(mime):
			line(r.keepPicture(ctx, server, data, mime))
		case data != "":
			raw, _ := base64.StdEncoding.DecodeString(data)
			line(fmt.Sprintf("[%s sent a %s file of %s, which the chat can't show.]", server, kindOf(mime), sizeOf(len(raw))))
		case c.URI != "":
			line(strings.TrimSpace(c.Name + " " + c.URI))
		}
	}
	return b.String()
}

// showableImage reports whether a type is a picture gbot will keep and
// draw. SVG is left out on purpose: it is a document, it can carry
// script, and gbot serves what a tool sent from its own origin.
func showableImage(mime string) bool {
	switch strings.TrimSpace(strings.ToLower(strings.Split(mime, ";")[0])) {
	case "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/bmp":
		return true
	}
	return false
}

// keepPicture saves a picture for the chat and returns the line the model
// reads in its place.
func (r *Runtime) keepPicture(ctx context.Context, server, data, mime string) string {
	rc := runFrom(ctx)
	raw, err := base64.StdEncoding.DecodeString(data)
	switch {
	case err != nil || len(raw) == 0:
		return fmt.Sprintf("[%s sent a picture gbot couldn't read.]", server)
	case len(raw) > store.MaxBlob:
		return fmt.Sprintf("[%s sent a picture of %s, too big to keep (the limit is %s).]", server, sizeOf(len(raw)), sizeOf(store.MaxBlob))
	case rc == nil:
		return fmt.Sprintf("[%s sent a picture.]", server)
	}
	id, err := r.store.AddBlob(context.WithoutCancel(ctx), &store.Blob{BotID: rc.botID, User: rc.userID, Mime: mime, Bytes: raw})
	if err != nil {
		r.log.Error("save picture", "err", err)
		return fmt.Sprintf("[%s sent a picture gbot couldn't keep.]", server)
	}
	rc.addPicture(picture{Blob: id, Mime: mime, From: server, Size: len(raw)})
	return fmt.Sprintf("[A picture from %s is in the chat where you are answering: %s, %s. The person can see it. Say what it shows; don't describe it as a file or a link.]", server, kindOf(mime), sizeOf(len(raw)))
}

// kindOf names a type the way a person would ("PNG", "a PDF").
func kindOf(mime string) string {
	mime = strings.TrimSpace(strings.ToLower(mime))
	if mime == "" {
		return "file"
	}
	if i := strings.Index(mime, ";"); i >= 0 {
		mime = mime[:i]
	}
	_, sub, ok := strings.Cut(mime, "/")
	if !ok || sub == "" {
		return mime
	}
	sub = strings.TrimPrefix(sub, "x-")
	if i := strings.Index(sub, "+"); i > 0 {
		sub = sub[:i]
	}
	return strings.ToUpper(sub)
}

// sizeOf is a byte count in words.
func sizeOf(n int) string {
	switch {
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%d KB", n/(1<<10))
	default:
		return fmt.Sprintf("%d bytes", n)
	}
}

// mcpProbe talks just enough streamable HTTP to initialize a server and
// list its tools.
type mcpProbe struct {
	url     string
	headers map[string]string
	client  *http.Client
	session string
	seq     int
}

func (p *mcpProbe) call(ctx context.Context, method string, params any, out any) error {
	p.seq++
	body := map[string]any{"jsonrpc": "2.0", "method": method}
	if params != nil {
		body["params"] = params
	}
	if !strings.HasPrefix(method, "notifications/") {
		body["id"] = p.seq
	}
	raw, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.url, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	for k, v := range p.headers {
		req.Header.Set(k, v)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	req.Header.Set("MCP-Protocol-Version", "2025-06-18")
	if p.session != "" {
		req.Header.Set("Mcp-Session-Id", p.session)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if sid := resp.Header.Get("Mcp-Session-Id"); sid != "" {
		p.session = sid
	}
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return fmt.Errorf("%s: %s", resp.Status, strings.TrimSpace(string(b)))
	}
	if out == nil {
		return nil
	}
	// One answer is read up to a generous cap, so a server that streams
	// without end can't fill the server's memory. Large tool lists and
	// results fit well within it.
	answer := io.LimitReader(resp.Body, maxMCPResponse)
	var msg struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		sc := bufio.NewScanner(answer)
		// A tool list can be far larger than bufio's 64 KB default; Notion's
		// is. A short buffer would silently drop the response.
		sc.Buffer(make([]byte, 64*1024), 16<<20)
		for sc.Scan() {
			line := strings.TrimSpace(sc.Text())
			if !strings.HasPrefix(line, "data:") {
				continue
			}
			var m struct {
				Result json.RawMessage `json:"result"`
				Error  *struct {
					Message string `json:"message"`
				} `json:"error"`
			}
			if json.Unmarshal([]byte(strings.TrimSpace(strings.TrimPrefix(line, "data:"))), &m) != nil {
				continue
			}
			// Skip notifications; the answer to this call has a result.
			if m.Result == nil && m.Error == nil {
				continue
			}
			msg = m
			break
		}
		if err := sc.Err(); err != nil {
			return fmt.Errorf("read response: %w", err)
		}
	} else if err := json.NewDecoder(answer).Decode(&msg); err != nil {
		if errors.Is(err, io.EOF) {
			return errors.New("the server sent an empty response")
		}
		if errors.Is(err, io.ErrUnexpectedEOF) {
			return fmt.Errorf("the server's response was cut off (over %d MB, or the connection closed)", maxMCPResponse>>20)
		}
		return err
	}
	if msg.Error != nil {
		return errors.New(msg.Error.Message)
	}
	if msg.Result == nil {
		return errors.New("the server sent no response")
	}
	return json.Unmarshal(msg.Result, out)
}

// maxMCPResponse caps one MCP answer.
const maxMCPResponse = 64 << 20

// probe connects one server and returns its tools. It fails with the
// server's own error, which is what the person needs to see.
func (r *Runtime) probe(s mcpServer) ([]mcpToolDef, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	headers := make(map[string]string, len(s.headers))
	for k, v := range s.headers {
		if str, ok := v.(string); ok {
			headers[k] = str
		}
	}
	p := &mcpProbe{url: s.url, headers: headers, client: netguard.Client(15 * time.Second)}
	if err := p.call(ctx, "initialize", map[string]any{
		"protocolVersion": "2025-06-18",
		"capabilities":    map[string]any{},
		"clientInfo":      map[string]any{"name": "gbot", "version": "1"},
	}, &map[string]any{}); err != nil {
		return nil, err
	}
	_ = p.call(ctx, "notifications/initialized", nil, nil)
	var list struct {
		Tools []mcpToolDef `json:"tools"`
	}
	if err := p.call(ctx, "tools/list", map[string]any{}, &list); err != nil {
		return nil, err
	}
	for i := range list.Tools {
		list.Tools[i].Name = s.prefix + "__" + list.Tools[i].Name
	}
	sort.Slice(list.Tools, func(i, j int) bool { return list.Tools[i].Name < list.Tools[j].Name })
	return list.Tools, nil
}

// WarmMCP marks a just-added or re-enabled server as connecting, so the
// panel can show it right away while the background sync probes it.
func (r *Runtime) WarmMCP(s *store.MCPServer, sealer *crypto.Sealer) {
	srv := decodeMCP(s, sealer)
	for _, v := range srv.headers {
		if str, ok := v.(string); ok {
			r.addSecret(str)
		}
	}
	if t := openOAuth(s, sealer); t != nil {
		r.addSecret(t.Access)
		if t.Refresh != "" {
			r.addSecret(t.Refresh)
		}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if st, ok := r.mcp[s.ID]; ok && !st.off {
		return // already known; don't undo a live connection
	}
	r.mcp[s.ID] = mcpState{srv: srv, status: "connecting"}
}

// ForgetMCP marks a server as going away, so the panel shows it off at
// once. Its live entry is kept until the sync so the agents are rebuilt
// without it; dropping it here would make the comparison miss it.
func (r *Runtime) ForgetMCP(id string) {
	r.mu.Lock()
	if st, ok := r.mcp[id]; ok {
		st.off = true
		st.status = "off"
		r.mcp[id] = st
	}
	r.mu.Unlock()
}

func (r *Runtime) MCPStatus(id string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if st, ok := r.mcp[id]; ok {
		return st.status
	}
	return "off"
}

// MCPTools lists the tool names a server registered.
func (r *Runtime) MCPTools(id string) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if st, ok := r.mcp[id]; ok && st.loaded {
		return append([]string(nil), st.tools...)
	}
	return nil
}

// MCPToolLabels lists the display names a server gave its tools.
func (r *Runtime) MCPToolLabels(id string) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.mcp[id]
	if !ok || !st.loaded {
		return nil
	}
	out := make([]string, len(st.defs))
	for i, d := range st.defs {
		out[i] = d.label()
	}
	return out
}

func (r *Runtime) mcpPrefixes(user string) map[string]bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[string]bool{}
	for _, st := range r.mcp {
		if st.srv.owner == user && st.loaded {
			out[st.srv.prefix] = true
		}
	}
	return out
}

// mcpName is the name the person gave the server behind a tool prefix.
func (r *Runtime) mcpName(prefix string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, st := range r.mcp {
		if st.srv.prefix == prefix {
			return st.srv.name
		}
	}
	return ""
}
