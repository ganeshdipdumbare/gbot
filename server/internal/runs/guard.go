package runs

import (
	"context"
	"fmt"
	"strings"
	"sync"

	"github.com/ganeshdipdumbare/kritrim"
)

// runCtx is what the guard knows about the run in progress. It travels
// in the context, so concurrent runs of the same bot never mix.
type runCtx struct {
	botID  string
	userID string // the person the run is for
	mode   string // ask | auto | never
	allow  map[string]bool
	block  map[string]bool
	// mcpPrefix lists the tool prefixes of MCP servers the person added;
	// other accounts' MCP tools are hidden from this run.
	mcpPrefix map[string]bool

	mu sync.Mutex
	// tainted is set once outside text (a webhook, a web page, an MCP
	// result) entered the run: from then on, changes need approval.
	tainted bool
	// sources are the pages this run opened, for the Sources section.
	sources []source
	// contract is what the person agreed the task means; evidence is what
	// the run actually did; receipt is the independent check's verdict.
	contract *contract
	evidence []evidence
	receipt  string
}

type source struct {
	Title string
	URL   string
}

func sourceKey(url string) string {
	u := strings.ToLower(url)
	if i := strings.IndexAny(u, "?#"); i >= 0 {
		u = u[:i]
	}
	return strings.TrimRight(u, "/")
}

func (rc *runCtx) addSource(title, url string) {
	url = strings.TrimSpace(url)
	if url == "" {
		return
	}
	key := sourceKey(url)
	if key == "" {
		return
	}
	rc.mu.Lock()
	defer rc.mu.Unlock()
	for i, s := range rc.sources {
		if sourceKey(s.URL) == key {
			if s.Title == "" && title != "" {
				rc.sources[i].Title = strings.TrimSpace(title)
			}
			return
		}
	}
	if len(rc.sources) < 30 {
		rc.sources = append(rc.sources, source{Title: strings.TrimSpace(title), URL: url})
	}
}

// sourcesMarkdown is the backstop for a web answer that did not cite its
// pages. Pages the answer already links are left out.
func (rc *runCtx) sourcesMarkdown(answer string) string {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	var lines []string
	for _, s := range rc.sources {
		if strings.Contains(answer, s.URL) || strings.Contains(answer, sourceKey(s.URL)) {
			continue
		}
		title := strings.ReplaceAll(strings.TrimSpace(s.Title), "]", "\\]")
		if title == "" {
			title = s.URL
		}
		lines = append(lines, "- ["+title+"]("+s.URL+")")
	}
	if len(lines) == 0 {
		return ""
	}
	return "\n\n**Sources**\n" + strings.Join(lines, "\n")
}

func (rc *runCtx) isTainted() bool { rc.mu.Lock(); defer rc.mu.Unlock(); return rc.tainted }
func (rc *runCtx) taint()          { rc.mu.Lock(); rc.tainted = true; rc.mu.Unlock() }

type rcKey struct{}

func withRun(ctx context.Context, rc *runCtx) context.Context {
	return context.WithValue(ctx, rcKey{}, rc)
}
func runFrom(ctx context.Context) *runCtx { rc, _ := ctx.Value(rcKey{}).(*runCtx); return rc }

// systemKey carries the prompt built for one run to the model hook: the
// agent is shared, so the prompt can't live in its Config.
type systemKey struct{}

func withSystem(ctx context.Context, s string) context.Context {
	return context.WithValue(ctx, systemKey{}, s)
}

// Tools that only touch the bot's own state never need approval.
var internalTools = map[string]bool{
	"remember": true, "recall": true, "current_time": true, "ask_user": true,
	"request_takeover": true, "use_skill": true, "routine_list": true,
	// Talking to the person's own bots; a tainted sender taints the
	// other bot's run, so its own approvals still apply.
	"message_bot": true,
}

// alwaysAsk stops for the person in every mode, unless they chose
// "always allow" for the tool. These change the bot's own setup or drive
// its whole screen; a silent Auto run is the wrong default.
var alwaysAsk = map[string]bool{
	"routine_create": true,
	"task_contract":  true,
	"vault_type":     true,
	// Opening a page leaves the bot's own state (the person's IP, cookies,
	// and whatever the site does), so it is a step worth a card even
	// though it writes nothing.
	"browser_navigate": true,
}

// needsApproval applies the requester's approval mode to one call.
func needsApproval(rc *runCtx, name string) bool {
	if internalTools[name] || rc.allow[name] || readonlyTool(name) {
		return false
	}
	// "Never ask" is an explicit choice, and it still wins.
	if rc.mode == "never" {
		return false
	}
	if alwaysAsk[name] {
		return true
	}
	if rc.mode == "ask" {
		return true
	}
	// auto: ask for any change once outside text entered the run.
	return rc.isTainted()
}

func describeCall(name string, args map[string]any) string {
	switch name {
	case "computer_shell":
		return "Run a command on its computer"
	case "computer_write_file":
		return fmt.Sprintf("Write %v on its computer", args["path"])
	case "browser_navigate":
		return fmt.Sprintf("Open %v in its browser", args["url"])
	case "browser_back":
		return "Go back a page in its browser"
	case "browser_forward":
		return "Go forward a page in its browser"
	case "browser_reload":
		return "Reload the page in its browser"
	case "browser_cookies":
		return "Dismiss a cookie prompt on a web page"
	case "browser_click":
		return fmt.Sprintf("Click [%v] on a web page", args["ref"])
	case "browser_type":
		return fmt.Sprintf("Type into [%v] on a web page", args["ref"])
	case "browser_press":
		return fmt.Sprintf("Press %v on a web page", args["key"])
	case "browser_check":
		return fmt.Sprintf("Change checkbox [%v] on a web page", args["ref"])
	case "routine_create":
		return fmt.Sprintf("Create the routine %q · %s", args["name"], args["schedule"])
	case "vault_type":
		return fmt.Sprintf("Type the saved secret %q into [%v] (its value is never shown)", args["secret"], args["ref"])
	case "routine_delete":
		return "Delete a routine"
	case "task_contract":
		var b strings.Builder
		b.WriteString("Agree on the plan: ")
		b.WriteString(clip(fmt.Sprint(args["goal"]), 160))
		if checks, ok := args["checks"].([]any); ok {
			for _, c := range checks {
				b.WriteString("\n- ")
				b.WriteString(clip(fmt.Sprint(c), 100))
			}
		}
		return b.String()
	}
	if i := strings.Index(name, "__"); i >= 0 {
		return "Use " + strings.ReplaceAll(name[i+2:], "_", " ")
	}
	return "Use " + strings.ReplaceAll(name, "_", " ")
}

// mcpTool reports the MCP server prefix behind a tool name, if any.
func mcpTool(name string) (string, bool) {
	i := strings.Index(name, "__")
	if i < 0 {
		return "", false
	}
	return name[:i], true
}

// approve decides one risky call. Everything that isn't marked risky
// never reaches here.
func (r *Runtime) approve(ctx context.Context, a kritrim.Approval) (kritrim.Decision, error) {
	rc := runFrom(ctx)
	if rc == nil {
		return kritrim.Approve(), nil
	}
	if prefix, ok := mcpTool(a.Tool); ok && !rc.mcpPrefix[prefix] {
		return kritrim.Reject("That tool isn't available here."), nil
	}
	if rc.block[a.Tool] {
		return kritrim.Reject("The person blocked this tool. Don't try it again; find another way or explain."), nil
	}
	// Questions and takeovers always stop for the person, in every mode.
	if a.Tool == "ask_user" || a.Tool == "request_takeover" {
		return kritrim.Pause(), nil
	}
	if !needsApproval(rc, a.Tool) {
		return kritrim.Approve(), nil
	}
	return kritrim.Pause(), nil
}

// cardKind names the card an interrupt becomes, or "" when the policy
// would have approved the call without asking.
func cardKind(rc *runCtx, in kritrim.Interrupt) string {
	switch in.Tool {
	case "ask_user":
		return "question"
	case "request_takeover":
		return "takeover"
	}
	if needsApproval(rc, in.Tool) {
		return "approval"
	}
	return ""
}

// beforeModel swaps in the run's own system prompt and hides tools this
// person may not use.
func (r *Runtime) beforeModel(ctx context.Context, e *kritrim.ModelEvent) error {
	if e.Request == nil {
		return nil
	}
	if s, _ := ctx.Value(systemKey{}).(string); s != "" {
		e.Request.System = s
	}
	e.Request.Messages = pairedTools(e.Request.Messages)
	rc := runFrom(ctx)
	if rc == nil {
		return nil
	}
	kept := e.Request.Tools[:0]
	for _, t := range e.Request.Tools {
		if prefix, ok := mcpTool(t.Name); ok && !rc.mcpPrefix[prefix] {
			continue
		}
		if rc.block[t.Name] {
			continue
		}
		kept = append(kept, t)
	}
	e.Request.Tools = kept
	return nil
}

// pairedTools drops tool results whose call is no longer in the prompt.
// Trimming old turns can cut between an assistant's tool call and its
// result; strict providers (the OpenAI Responses API) reject the orphan.
func pairedTools(msgs []kritrim.Message) []kritrim.Message {
	seen := map[string]bool{}
	clean := true
	for _, m := range msgs {
		for _, c := range m.ToolCalls {
			seen[c.ID] = true
		}
		if m.Role == kritrim.RoleTool && !seen[m.ToolCallID] {
			clean = false
		}
	}
	if clean {
		return msgs
	}
	seen = map[string]bool{}
	out := make([]kritrim.Message, 0, len(msgs))
	for _, m := range msgs {
		if m.Role == kritrim.RoleTool && !seen[m.ToolCallID] {
			continue
		}
		for _, c := range m.ToolCalls {
			seen[c.ID] = true
		}
		out = append(out, m)
	}
	return out
}

// beforeTool is the last line of defence for a blocked or foreign tool.
func (r *Runtime) beforeTool(ctx context.Context, e *kritrim.ToolEvent) error {
	rc := runFrom(ctx)
	if rc == nil || e.Call == nil {
		return nil
	}
	if prefix, ok := mcpTool(e.Call.Name); ok && !rc.mcpPrefix[prefix] {
		e.Deny = "That tool isn't available here."
		return nil
	}
	if rc.block[e.Call.Name] {
		e.Deny = "The person blocked this tool. Don't try it again; find another way or explain."
	}
	return nil
}

// afterTool marks the run as having read outside text.
func (r *Runtime) afterTool(ctx context.Context, e *kritrim.ToolEvent) {
	rc := runFrom(ctx)
	if rc == nil || e.Call == nil {
		return
	}
	if untrustedTool(e.Call.Name) {
		rc.taint()
	}
	if e.Result == nil {
		return
	}
	r.captureEvidence(rc, e)
	// The navigate argument is the exact URL; the Source line adds the
	// title and covers clicks, redirects and later pages.
	if e.Call.Name == "browser_navigate" {
		if u, ok := e.Args["url"].(string); ok {
			rc.addSource("", u)
		}
	}
	for _, line := range strings.Split(e.Result.Content, "\n") {
		rest, ok := strings.CutPrefix(line, "Source: ")
		if !ok {
			continue
		}
		if i := strings.LastIndex(rest, " — "); i >= 0 {
			rc.addSource(rest[:i], rest[i+len(" — "):])
		}
	}
}
