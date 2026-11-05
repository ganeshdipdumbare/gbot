package runs

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"

	"github.com/ganeshdipdumbare/kiln/engine"
	"github.com/ganeshdipdumbare/kiln/sdk"
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
}

func (rc *runCtx) isTainted() bool { rc.mu.Lock(); defer rc.mu.Unlock(); return rc.tainted }
func (rc *runCtx) taint()          { rc.mu.Lock(); rc.tainted = true; rc.mu.Unlock() }

type rcKey struct{}

func withRun(ctx context.Context, rc *runCtx) context.Context {
	return context.WithValue(ctx, rcKey{}, rc)
}
func runFrom(ctx context.Context) *runCtx { rc, _ := ctx.Value(rcKey{}).(*runCtx); return rc }

// Tools that only touch the bot's own state never need approval.
var internalTools = map[string]bool{
	"remember": true, "recall": true, "current_time": true, "ask_user": true,
	"request_takeover": true, "use_skill": true, "routine_list": true,
	// Talking to the person's own bots; a tainted sender taints the
	// other bot's run, so its own approvals still apply.
	"message_bot": true,
}

func newID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func metaBool(t *sdk.Tool, k string) bool {
	if t == nil || t.Meta == nil {
		return false
	}
	v, _ := t.Meta[k].(bool)
	return v
}

func metaString(t *sdk.Tool, k string) string {
	if t == nil || t.Meta == nil {
		return ""
	}
	v, _ := t.Meta[k].(string)
	return v
}

// needsApproval applies the requester's approval mode to one call.
func needsApproval(rc *runCtx, name string, t *sdk.Tool) bool {
	if internalTools[name] || rc.allow[name] {
		return false
	}
	readonly := metaBool(t, "readonly")
	switch rc.mode {
	case "never":
		return false
	case "ask":
		return !readonly
	}
	// auto: ask for irreversible actions, and for any change once
	// outside text entered the run.
	if metaString(t, "risk") == "high" {
		return true
	}
	if rc.isTainted() && !readonly {
		return true
	}
	return false
}

func describeCall(name string, t *sdk.Tool, args map[string]any) string {
	switch name {
	case "computer_shell":
		return "Run a command on its computer"
	case "computer_write_file":
		return fmt.Sprintf("Write %v on its computer", args["path"])
	case "browser_open":
		return fmt.Sprintf("Open %v in its browser", args["url"])
	case "browser_navigate":
		return fmt.Sprintf("Open %v in its browser", args["url"])
	case "browser_back":
		return "Go back a page in its browser"
	case "browser_forward":
		return "Go forward a page in its browser"
	case "browser_reload":
		return "Reload the page in its browser"
	case "browser_click":
		return fmt.Sprintf("Click [%v] on a web page", args["ref"])
	case "browser_type":
		return fmt.Sprintf("Type into [%v] on a web page", args["ref"])
	case "browser_press":
		return fmt.Sprintf("Press %v on a web page", args["key"])
	case "browser_check":
		return fmt.Sprintf("Change checkbox [%v] on a web page", args["ref"])
	case "browser_read":
		return fmt.Sprintf("Read %v", args["url"])
	case "routine_create":
		return fmt.Sprintf("Create the routine %q", args["name"])
	case "routine_delete":
		return "Delete a routine"
	}
	if srv := metaString(t, "mcp_server"); srv != "" {
		return "Use " + strings.ReplaceAll(strings.SplitN(name, "__", 2)[len(strings.SplitN(name, "__", 2))-1], "_", " ")
	}
	return "Use " + strings.ReplaceAll(name, "_", " ")
}

// guardModule enforces approvals and scoping on every tool call. It is
// compiled into gbot and loaded into the shared agent.
var guardModule = engine.Module{Name: "gbot_guard", Apply: func(ctx *sdk.Module) error {
	ctx.SetPriority(5) // inside policy/audit, outside retries and logging

	// Hide other accounts' MCP tools from the model.
	ctx.BeforeStep(func(c context.Context, sc *sdk.StepContext) error {
		rc := runFrom(c)
		if rc == nil || sc.Request == nil {
			return nil
		}
		kept := sc.Request.Tools[:0]
		for _, t := range sc.Request.Tools {
			if srv := metaString(&t, "mcp_server"); srv != "" && !rc.mcpPrefix[srv] {
				continue
			}
			if rc.block[t.Name] {
				continue
			}
			kept = append(kept, t)
		}
		sc.Request.Tools = kept
		return nil
	})

	ctx.WrapTool(func(c context.Context, req *sdk.ToolRequest, next sdk.ToolHandler) (*sdk.ToolResult, error) {
		rc := runFrom(c)
		if rc == nil {
			return next(c, req)
		}
		name := req.Call.Name
		if srv := metaString(req.Tool, "mcp_server"); srv != "" && !rc.mcpPrefix[srv] {
			return &sdk.ToolResult{Content: "That tool isn't available here.", IsError: true}, nil
		}
		if rc.block[name] {
			return &sdk.ToolResult{Content: "The person blocked this tool. Don't try it again; find another way or explain.", IsError: true}, nil
		}
		approved := req.Decision != nil && (req.Decision.Type == "approve" || req.Decision.Type == "edit")
		if req.Decision == nil {
			switch name {
			case "ask_user":
				q, _ := req.Call.Args["question"].(string)
				return &sdk.ToolResult{Interrupt: &sdk.Interrupt{ID: newID(), Kind: "question", ToolCall: &req.Call,
					Message: q, Allowed: []string{"respond"}, Payload: map[string]any{"options": req.Call.Args["options"]}}}, nil
			case "request_takeover":
				why, _ := req.Call.Args["reason"].(string)
				return &sdk.ToolResult{Interrupt: &sdk.Interrupt{ID: newID(), Kind: "takeover", ToolCall: &req.Call,
					Message: why, Allowed: []string{"respond"}}}, nil
			}
		}
		if !approved && needsApproval(rc, name, req.Tool) {
			return &sdk.ToolResult{Interrupt: &sdk.Interrupt{ID: newID(), Kind: "approval", ToolCall: &req.Call,
				Message: describeCall(name, req.Tool, req.Call.Args), Allowed: []string{"approve", "reject"}}}, nil
		}
		res, err := next(c, req)
		if metaBool(req.Tool, "untrusted") || metaString(req.Tool, "mcp_server") != "" {
			rc.taint()
		}
		return res, err
	})
	return nil
}}
