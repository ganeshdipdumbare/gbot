package runs

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/ganeshdipdumbare/kritrim"
)

// PDD Kit applies proof-driven development to code. General bots cannot
// prove arbitrary work, but the useful half transfers: agree on an intent
// contract, check the run's evidence independently, and show a receipt.

type contract struct {
	Goal   string
	Checks []string
}

type evidence struct {
	Tool   string
	Result string
}

func (rc *runCtx) setContract(c *contract) {
	rc.mu.Lock()
	rc.contract = c
	rc.mu.Unlock()
}

func (rc *runCtx) getContract() *contract {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	return rc.contract
}

func (rc *runCtx) addEvidence(tool, result string) {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	rc.evidence = append(rc.evidence, evidence{Tool: tool, Result: result})
	if len(rc.evidence) > 24 {
		rc.evidence = rc.evidence[len(rc.evidence)-24:]
	}
}

func (rc *runCtx) snapshotEvidence() []evidence {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	return append([]evidence(nil), rc.evidence...)
}

func (rc *runCtx) snapshotSources() []source {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	return append([]source(nil), rc.sources...)
}

func (rc *runCtx) setReceipt(s string) {
	rc.mu.Lock()
	rc.receipt = s
	rc.mu.Unlock()
}

func (rc *runCtx) getReceipt() string {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	return rc.receipt
}

// captureEvidence keeps what the run actually did, with typed text and
// secrets left out. The independent verifier sees this, not the bot's
// own claims.
func (r *Runtime) captureEvidence(rc *runCtx, e *kritrim.ToolEvent) {
	switch e.Call.Name {
	case "ask_user", "request_takeover", "routine_list", "routine_run", "routine_create",
		"routine_update", "routine_delete", "task_contract", "task_verify", "remember", "recall",
		"current_time", "message_bot", "vault_type":
		return
	}
	name := e.Call.Name
	switch name {
	case "computer_shell":
		name = "shell: " + clip(fmt.Sprint(e.Args["command"]), 200)
	case "computer_read_file", "computer_write_file":
		name += ": " + fmt.Sprint(e.Args["path"])
	case "browser_navigate":
		name = "opened " + fmt.Sprint(e.Args["url"])
	case "browser_click":
		name = fmt.Sprintf("clicked [%v]", e.Args["ref"])
	case "browser_type":
		name = fmt.Sprintf("typed into [%v]", e.Args["ref"])
	case "browser_press":
		name = fmt.Sprintf("pressed %v", e.Args["key"])
	case "browser_snapshot":
		name = "read the page"
	}
	rc.addEvidence(name, r.redact(clip(strings.TrimSpace(e.Result.Content), 800)))
}

// verifyTask asks the same model, in a separate call with no tools or
// session, to judge the evidence against the contract. It cannot run
// anything, so it can only say what the evidence supports.
func (r *Runtime) verifyTask(ctx context.Context, j *Job, c *contract, ev []evidence, sources []source) (string, error) {
	m, err := r.modelFor(j.model)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	b.WriteString("You are an independent verifier. Do not trust the agent's claims; judge only the evidence below.\n\n")
	b.WriteString("The person asked:\n" + clip(j.Prompt, 2000) + "\n\n")
	b.WriteString("Agreed goal:\n" + clip(c.Goal, 500) + "\n\nChecks:\n")
	for i, ch := range c.Checks {
		fmt.Fprintf(&b, "%d. %s\n", i+1, ch)
	}
	b.WriteString("\nEvidence (tool calls and their output):\n")
	if len(ev) == 0 {
		b.WriteString("(none)\n")
	}
	for _, e := range ev {
		fmt.Fprintf(&b, "- %s: %s\n", e.Tool, e.Result)
	}
	if len(sources) > 0 {
		b.WriteString("\nPages opened:\n")
		for _, s := range sources {
			fmt.Fprintf(&b, "- %s %s\n", s.Title, s.URL)
		}
	}
	b.WriteString(`
For each check, answer pass, fail or unverified (unverified means the evidence cannot show it, such as a submission the agent cannot see). Give a short evidence quote for each. Reply as JSON only:
{"checks":[{"check":"...","status":"pass|fail|unverified","evidence":"..."}],"summary":"..."}`)
	res, err := m.Generate(ctx, kritrim.Request{
		Messages:  []kritrim.Message{{Role: kritrim.RoleUser, Content: b.String()}},
		MaxTokens: 1200,
	})
	if err != nil {
		return "", err
	}
	raw := strings.TrimSpace(res.Text)
	raw = strings.TrimPrefix(raw, "```json")
	raw = strings.TrimPrefix(raw, "```")
	raw = strings.TrimSuffix(raw, "```")
	var out struct {
		Checks []struct {
			Check    string `json:"check"`
			Status   string `json:"status"`
			Evidence string `json:"evidence"`
		} `json:"checks"`
		Summary string `json:"summary"`
	}
	if err := json.Unmarshal([]byte(strings.TrimSpace(raw)), &out); err != nil || len(out.Checks) == 0 {
		return "**Verification**\nCould not verify automatically. The evidence is in the chat's activity; check it before relying on this.", nil
	}
	var lines []string
	passed, failed := 0, 0
	for _, ch := range out.Checks {
		icon := "⚠️"
		switch strings.ToLower(ch.Status) {
		case "pass":
			icon, passed = "✅", passed+1
		case "fail":
			icon, failed = "❌", failed+1
		}
		line := "- " + icon + " " + clip(ch.Check, 160)
		if ch.Evidence != "" {
			line += " — " + clip(ch.Evidence, 240)
		}
		lines = append(lines, line)
	}
	receipt := "**Verification**\n" + strings.Join(lines, "\n")
	if out.Summary != "" {
		receipt += "\n\n" + clip(out.Summary, 400)
	}
	if failed > 0 {
		receipt += "\n\nNot every check passed; do not claim the task is done."
	} else if passed == 0 {
		receipt += "\n\nNo check could be confirmed from the evidence."
	}
	return receipt, nil
}
