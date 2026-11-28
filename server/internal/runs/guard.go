package runs

import (
	"context"
	"fmt"
	"net/url"
	"regexp"
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

	// steer takes the messages steered into this turn, if it takes any.
	steer func() []Job

	mu sync.Mutex
	// tainted is set once outside text (a webhook, a web page, an MCP
	// result) entered the run: from then on, changes need approval.
	tainted bool
	// fetchable are the pages web_fetch may read in this run.
	fetchable map[string]bool
	// seen are the https addresses that appeared in a tool's answer this
	// run. A result card's picture is fetched only from one of these, so
	// the bot can't compose an address that carries what it knows.
	seen map[string]bool
	// sources are the pages this run opened, for the Sources section.
	sources []source
	// pictures are what a tool handed back for the person to look at,
	// waiting to be shown in the chat.
	pictures []picture
	// results are tool answers that were lists of records, kept as cards
	// for the chat (see results.go).
	results []resultSet
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
	url = sourceURL(url)
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

// A picture a tool handed back: saved, and waiting for the chat.
type picture struct {
	Blob string
	Mime string
	From string // the integration that sent it
	Size int
}

func (rc *runCtx) addPicture(p picture) {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	rc.pictures = append(rc.pictures, p)
}

// takePictures hands over what is waiting, leaving nothing behind: each
// picture is shown once.
func (rc *runCtx) takePictures() []picture {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	out := rc.pictures
	rc.pictures = nil
	return out
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
		title := mdEscaper.Replace(strings.TrimSpace(s.Title))
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

// sourceURL keeps an http(s) address as one Markdown link target: no
// spaces, and parentheses encoded so the link can't be closed early.
func sourceURL(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return ""
	}
	return strings.NewReplacer(" ", "%20", "(", "%28", ")", "%29", "<", "%3C", ">", "%3E").Replace(u.String())
}

// mdEscaper keeps a page title inside its link text.
var mdEscaper = strings.NewReplacer("\\", "\\\\", "[", "\\[", "]", "\\]", "\n", " ", "\r", " ")

// allowFetch records a page web_fetch may read: a search result, or a link
// the person wrote. Both are nil-safe so tools can run without a run.
func (rc *runCtx) allowFetch(u string) {
	if rc == nil {
		return
	}
	rc.mu.Lock()
	defer rc.mu.Unlock()
	if rc.fetchable == nil {
		rc.fetchable = map[string]bool{}
	}
	rc.fetchable[fetchKey(u)] = true
}

// fetchKey is one spelling of a page address, so a link the person pasted
// (Mü nchen as written) and the same one the bot passes (percent-encoded) match.
func fetchKey(u string) string {
	if n := safeLink(u); n != "" {
		u = n
	}
	// The path is already percent-encoded by parsing; do the query too, so
	// ?q=München and ?q=M%C3%BCnchen are one address.
	if parsed, err := url.Parse(u); err == nil && parsed.RawQuery != "" {
		if q, err := url.ParseQuery(parsed.RawQuery); err == nil {
			parsed.RawQuery = q.Encode()
			u = parsed.String()
		}
	}
	return strings.TrimRight(u, "/")
}

// see records the https addresses in a tool's answer.
func (rc *runCtx) see(text string) {
	if rc == nil {
		return
	}
	found := seenLinkRE.FindAllString(text, 200)
	if len(found) == 0 {
		return
	}
	rc.mu.Lock()
	defer rc.mu.Unlock()
	if rc.seen == nil {
		rc.seen = map[string]bool{}
	}
	for _, u := range found {
		rc.seen[fetchKey(strings.TrimRight(u, ".,;:!?)]}"))] = true
	}
}

var seenLinkRE = regexp.MustCompile(`https://[^\s<>"'\x60]+`)

// mayShow reports whether a card picture's address came from a tool's
// answer or the person in this run.
func (rc *runCtx) mayShow(u string) bool {
	if rc == nil {
		return false
	}
	k := fetchKey(u)
	rc.mu.Lock()
	defer rc.mu.Unlock()
	return rc.seen[k] || rc.fetchable[k]
}

func (rc *runCtx) mayFetch(u string) bool {
	if rc == nil {
		return false
	}
	rc.mu.Lock()
	defer rc.mu.Unlock()
	return rc.fetchable[fetchKey(u)]
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
	// Changing or firing a routine is the same capability as creating one:
	// a stored prompt that later runs unattended.
	"routine_update": true,
	"routine_run":    true,
	// Deleting one stops work the person set up to run on its own; the
	// phone asks for it too.
	"routine_delete": true,
	"task_contract":  true,
	"vault_type":     true,
	// Opening a page leaves the bot's own state (the person's IP, cookies,
	// and whatever the site does), so it is a step worth a card even
	// though it writes nothing.
	"browser_navigate": true,
	// The roster is the person's own team. The main bot proposes; they
	// decide, in every mode, as phone mode does.
	"bot_create":  true,
	"bot_update":  true,
	"bot_archive": true,
}

// noBlanketAllow are tools a single "always allow" never covers: they act on
// the person's machine, accounts or secrets, and a rule for them would
// silently apply to every bot the person has, now and later.
var noBlanketAllow = map[string]bool{
	"computer_shell":      true,
	"computer_write_file": true,
	"vault_type":          true,
	"browser_navigate":    true,
	"routine_create":      true,
	"routine_update":      true,
	"routine_run":         true,
	"task_contract":       true,
	// The roster rewrites what the person's bots (and teammates on shared
	// bots) are told to do; archiving takes a teammate away.
	"bot_create":  true,
	"bot_update":  true,
	"bot_archive": true,
}

// CanAlwaysAllow reports whether "always allow" may be offered for a tool.
func CanAlwaysAllow(tool string) bool { return !noBlanketAllow[tool] }

// needsApproval applies the requester's approval mode to one call.
func needsApproval(rc *runCtx, name string) bool {
	// A rule for a tool that may never be blanket-allowed is ignored, however
	// it was stored.
	if internalTools[name] || (rc.allow[name] && CanAlwaysAllow(name)) || readonlyTool(name) {
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
	case "routine_update":
		return fmt.Sprintf("Change a routine · %s", clip(fmt.Sprint(args["prompt"]), 200))
	case "routine_run":
		return "Run a routine now"
	case "bot_create":
		return fmt.Sprintf("Hatch a bot: %v", args["name"])
	case "bot_update":
		return fmt.Sprintf("Change %v", args["bot"])
	case "bot_archive":
		return fmt.Sprintf("Archive %v", args["bot"])
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
	// Last, so a steered message is never read as the tool's own output
	// (a Source line in it doesn't count as a page the run opened).
	defer r.deliverSteered(rc, e)
	if untrustedTool(e.Call.Name) {
		rc.taint()
	}
	// A shell fetch returns web text, and history or memory can hold text
	// that came from outside, so these taint by what they return.
	if e.Call.Name == "computer_shell" {
		if c, _ := e.Args["command"].(string); fetchesWeb(c) {
			rc.taint()
		}
	}
	if readsStoredOutside[e.Call.Name] {
		rc.taint()
	}
	if e.Result == nil {
		return
	}
	r.captureEvidence(rc, e)
	if e.Call.Name != "show_results" {
		rc.see(e.Result.Content)
	}
	// The navigate argument is the exact URL; the Source line adds the
	// title and covers clicks, redirects and later pages.
	if e.Call.Name == "browser_navigate" {
		if u, ok := e.Args["url"].(string); ok {
			rc.addSource("", u)
		}
	}
	// Only the browser prints Source lines; any other tool's text (a page
	// read with web_fetch, an MCP result, a file) is not trusted to add one.
	if !strings.HasPrefix(e.Call.Name, "browser_") {
		return
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

// readsStoredOutside names read-only tools whose output can carry text that
// entered the bot from outside: earlier chat messages (including Slack and
// webhook prompts), memory notes, and files the person or a tool wrote.
var readsStoredOutside = map[string]bool{
	"history_search":     true,
	"recall":             true,
	"computer_read_file": true,
}
