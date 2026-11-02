package runs

import (
	"fmt"
	"net/url"
	"regexp"
	"strings"

	"github.com/ganeshdipdumbare/eipagent/sdk"
)

// A step is one thing a bot did with a tool, said plainly, for the
// activity summary that sits above its reply ("Ran python3 fib.py ·
// exit 0", "Wrote fib.py").
type step struct {
	Label  string `json:"label"`
	Detail string `json:"detail,omitempty"`
	OK     bool   `json:"ok"`
}

var exitCode = regexp.MustCompile(`^exit (\d+)`)

func short(s string, n int) string {
	s = strings.Join(strings.Fields(s), " ")
	if len([]rune(s)) <= n {
		return s
	}
	return string([]rune(s)[:n-1]) + "…"
}

func hostPath(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return short(raw, 48)
	}
	return short(strings.TrimPrefix(u.Host, "www.")+strings.TrimSuffix(u.Path, "/"), 48)
}

// describeStep turns a finished tool call into a summary line. It
// returns false for calls that don't belong in the summary (questions,
// takeovers and messages to other bots have their own cards; calls to
// tools that don't exist are the model correcting itself). connector
// names the MCP server behind a tool prefix, so its tools read apart
// from built-in ones.
func describeStep(call *sdk.ToolCall, result string, connector func(prefix string) string) (step, bool) {
	if call == nil || strings.HasPrefix(result, "error: unknown tool ") {
		return step{}, false
	}
	a := call.Args
	str := func(k string) string { v, _ := a[k].(string); return v }
	failed := strings.HasPrefix(strings.ToLower(result), "error") || strings.Contains(result, "isn't available") || strings.Contains(result, "blocked this tool")
	st := step{OK: !failed}
	switch call.Name {
	case "ask_user", "request_takeover", "message_bot":
		return step{}, false
	case "computer_shell":
		st.Label, st.Detail = "Ran", short(str("command"), 60)
		if m := exitCode.FindStringSubmatch(result); m != nil {
			st.Detail += " · exit " + m[1]
			st.OK = m[1] == "0"
		}
	case "computer_write_file":
		st.Label, st.Detail = "Wrote", short(str("path"), 60)
	case "computer_read_file":
		st.Label, st.Detail = "Read", short(str("path"), 60)
	case "browser_open":
		st.Label, st.Detail = "Opened", hostPath(str("url"))
	case "browser_read":
		st.Label, st.Detail = "Read", hostPath(str("url"))
	case "remember":
		st.Label, st.Detail = "Remembered", short(str("text"), 60)
	case "recall":
		st.Label, st.Detail = "Checked memory", short(str("query"), 60)
	case "current_time":
		st.Label = "Checked the time"
	case "routine_create":
		st.Label, st.Detail = "Scheduled", short(str("name"), 60)
	case "routine_list":
		st.Label = "Checked routines"
	case "routine_delete":
		st.Label = "Deleted a routine"
	case "use_skill":
		st.Label, st.Detail = "Used skill", str("name")
	default:
		name, server := call.Name, ""
		if i := strings.Index(name, "__"); i >= 0 {
			name, server = name[i+2:], connector(name[:i])
		}
		st.Label = "Used " + strings.ReplaceAll(name, "_", " ")
		if server != "" {
			st.Label += " · " + server
		}
		if failed {
			st.Detail = short(result, 60)
		}
	}
	if failed && st.Detail == "" {
		st.Detail = short(result, 60)
	}
	return st, true
}

// stepsText is a plain-text fallback for clients that don't render the
// summary (and for search).
func stepsText(steps []step) string {
	var b strings.Builder
	for i, s := range steps {
		if i > 0 {
			b.WriteString("\n")
		}
		mark := "✓"
		if !s.OK {
			mark = "✕"
		}
		fmt.Fprintf(&b, "%s %s", mark, s.Label)
		if s.Detail != "" {
			b.WriteString(" → " + s.Detail)
		}
	}
	return b.String()
}
