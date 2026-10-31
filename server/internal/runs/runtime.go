package runs

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/ganeshdipdumbare/eipagent"
	_ "github.com/ganeshdipdumbare/eipagent/drivers/postgres"
	"github.com/ganeshdipdumbare/eipagent/engine"
	"github.com/ganeshdipdumbare/eipagent/sdk"

	"gbot/internal/models"
	"gbot/internal/store"
)

// Presence is what a bot's avatar shows.
type Presence struct {
	State  string `json:"state"` // idle | thinking | working | waiting | blocked | done
	Detail string `json:"detail,omitempty"`
	Queued int    `json:"queued,omitempty"`
}

type Job struct {
	Bot     *store.Bot
	Prompt  string
	Model   string
	Trigger string // chat | routine | webhook
}

type Config struct {
	DatabaseURL string
	OllamaHost  string
	MaxSteps    int
	// Mock loads eipagent's offline mock model ("mock:*"), for tests.
	Mock bool
}

// Runtime owns one shared eipagent agent. Bots differ per run (system
// prompt, model, memory namespace, thread), which keeps every bot cheap:
// no per-bot plugin loading, one connection pool, one audit trail.
type Runtime struct {
	agent   *eipagent.Agent
	store   *store.Store
	hub     *Hub
	catalog *models.Catalog
	log     *slog.Logger

	mu       sync.Mutex
	workers  map[string]*worker
	presence map[string]Presence
}

type worker struct {
	queue  []Job
	cancel context.CancelFunc
}

func New(cfg Config, st *store.Store, hub *Hub, cat *models.Catalog, log *slog.Logger) (*Runtime, error) {
	// eipagent's db_sql reads its DSN as a secret, granted to it alone.
	os.Setenv("EIP_DATABASE_URL", cfg.DatabaseURL)
	sdk.AddRedaction(cfg.DatabaseURL)
	grants := map[string]any{"db_sql": []any{"EIP_DATABASE_URL"}}
	opts := []eipagent.Option{
		eipagent.WithPreset("secure"),
		eipagent.WithMaxSteps(max(cfg.MaxSteps, 1)),
		eipagent.WithLogger(func(scope, msg string) { log.Debug(msg, "plugin", scope) }),
		// Local models are the default and need no key.
		eipagent.WithPluginSpec(engine.PluginSpec{Use: "llm_openai", As: "llm_ollama", Config: map[string]any{
			"name": "ollama", "base_url": strings.TrimRight(cfg.OllamaHost, "/") + "/v1", "api_key": "",
		}}),
	}
	if cfg.Mock {
		opts = append(opts, eipagent.WithPlugin("llm_mock", map[string]any{"name": "mock"}))
	}
	for _, p := range models.PaidProviders() {
		spec, key := paidSpec(p)
		grants[spec.As] = []any{key}
		opts = append(opts, eipagent.WithPluginSpec(spec))
	}
	opts = append(opts,
		eipagent.WithPlugin("secrets_env", map[string]any{"allow": []any{"EIP_DATABASE_URL", "*_API_KEY"}, "grants": grants}),
		eipagent.WithPlugins("db_sql", "checkpoint_sql", "store_sql", "memory_store", "tool_time"),
		eipagent.WithPlugin("mw_retry", map[string]any{"max_retries": 2}),
		eipagent.WithPlugin("mw_limits", map[string]any{"model_calls_run": 24, "tool_calls_run": 48}),
		eipagent.WithPlugin("mw_summarize", map[string]any{"trigger_messages": 60, "keep_messages": 20}),
		// Default model only matters for the agent's own bookkeeping;
		// every run passes the bot's model.
		eipagent.WithModel("ollama:default"),
	)
	a, err := eipagent.New(opts...)
	if err != nil {
		return nil, err
	}
	return &Runtime{agent: a, store: st, hub: hub, catalog: cat, log: log,
		workers: map[string]*worker{}, presence: map[string]Presence{}}, nil
}

func paidSpec(p string) (engine.PluginSpec, string) {
	switch p {
	case "anthropic":
		return engine.PluginSpec{Use: "llm_anthropic", As: "llm_anthropic", Config: map[string]any{"name": "anthropic"}}, "ANTHROPIC_API_KEY"
	case "gemini":
		return engine.PluginSpec{Use: "llm_gemini", As: "llm_gemini", Config: map[string]any{"name": "gemini"}}, "GEMINI_API_KEY"
	case "openai":
		return engine.PluginSpec{Use: "llm_openai", As: "llm_openai", Config: map[string]any{"name": "openai"}}, "OPENAI_API_KEY"
	}
	urls := map[string]string{"deepseek": "https://api.deepseek.com", "groq": "https://api.groq.com/openai/v1", "openrouter": "https://openrouter.ai/api/v1"}
	key := strings.ToUpper(p) + "_API_KEY"
	return engine.PluginSpec{Use: "llm_openai", As: "llm_" + p, Config: map[string]any{
		"name": p, "base_url": urls[p], "api_key": "secret://" + key,
	}}, key
}

func (r *Runtime) Close() error { return r.agent.Close() }

func threadID(botID string) string { return "bot:" + botID }

// PresenceSnapshot returns the current presence of every busy bot.
func (r *Runtime) PresenceSnapshot() map[string]Presence {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make(map[string]Presence, len(r.presence))
	for k, v := range r.presence {
		out[k] = v
	}
	return out
}

func (r *Runtime) setPresence(b *store.Bot, p Presence) {
	r.mu.Lock()
	if w := r.workers[b.ID]; w != nil {
		p.Queued = len(w.queue)
	}
	if p.State == "idle" {
		delete(r.presence, b.ID)
	} else {
		r.presence[b.ID] = p
	}
	r.mu.Unlock()
	r.hub.Publish(b.SpaceID, Event{Type: "presence", BotID: b.ID, Data: p})
}

// Enqueue runs the job after any run already in progress for the bot.
// People can keep chatting while a bot works; messages run in order.
func (r *Runtime) Enqueue(j Job) {
	r.mu.Lock()
	w := r.workers[j.Bot.ID]
	if w != nil {
		w.queue = append(w.queue, j)
		r.mu.Unlock()
		return
	}
	w = &worker{}
	r.workers[j.Bot.ID] = w
	r.mu.Unlock()
	go r.drain(j, w)
}

func (r *Runtime) drain(j Job, w *worker) {
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
		r.mu.Lock()
		w.cancel = cancel
		r.mu.Unlock()
		r.run(ctx, j)
		cancel()
		r.mu.Lock()
		if len(w.queue) == 0 {
			delete(r.workers, j.Bot.ID)
			r.mu.Unlock()
			return
		}
		j, w.queue = w.queue[0], w.queue[1:]
		r.mu.Unlock()
	}
}

// Stop cancels the bot's current run and drops queued messages.
func (r *Runtime) Stop(botID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	w := r.workers[botID]
	if w == nil {
		return false
	}
	w.queue = nil
	if w.cancel != nil {
		w.cancel()
	}
	return true
}

func (r *Runtime) systemPrompt(b *store.Bot) string {
	var sb strings.Builder
	fmt.Fprintf(&sb, "You are %s", b.Name)
	if b.Title != "" {
		fmt.Fprintf(&sb, ", %s", b.Title)
	}
	sb.WriteString(`. You are a persistent AI teammate in gbot: one continuous conversation with the person you work for, with your own long-term memory.
Be warm, upbeat and concise. Use Markdown when it helps (lists, tables, code). Do the work rather than describing it.
Use the remember tool to save durable facts and preferences, and recall to look them up before asking again.
Never claim you did something you did not do.`)
	fmt.Fprintf(&sb, "\nToday is %s.", time.Now().Format("Monday, 2 January 2006"))
	if s := strings.TrimSpace(b.Instructions); s != "" {
		sb.WriteString("\n\nYour standing instructions from the person:\n")
		sb.WriteString(s)
	}
	return sb.String()
}

var toolLabels = map[string]string{
	"remember":     "Writing a note to memory",
	"recall":       "Checking memory",
	"current_time": "Checking the time",
}

func toolLabel(name string) string {
	if l, ok := toolLabels[name]; ok {
		return l
	}
	return "Using " + strings.ReplaceAll(name, "_", " ")
}

func (r *Runtime) run(ctx context.Context, j Job) {
	b := j.Bot
	model := j.Model
	if model == "" {
		model = r.catalog.Pick(ctx, b.Model)
	}
	if model == "" {
		r.botError(b, "", "No model is connected yet. Start Ollama (or add a provider key) and pick a model in Settings.")
		return
	}
	runID, err := r.store.StartRun(ctx, b.ID, j.Trigger, model)
	if err != nil {
		r.log.Error("start run", "err", err)
		return
	}
	r.setPresence(b, Presence{State: "thinking"})

	var cur *store.Message
	var text strings.Builder
	flush := func() {
		if cur == nil {
			return
		}
		content := strings.TrimSpace(text.String())
		_ = r.store.SetMessageContent(context.WithoutCancel(ctx), cur.ID, content)
		cur.Content = content
		r.hub.Publish(b.SpaceID, Event{Type: "message_done", BotID: b.ID, Data: cur})
		cur = nil
		text.Reset()
	}

	s := r.agent.Thread(threadID(b.ID)).Stream(ctx, j.Prompt,
		eipagent.Model(model),
		eipagent.System(r.systemPrompt(b)),
		// Memory is namespaced by user; each bot gets its own.
		eipagent.AsUser(threadID(b.ID)),
	)
	for e := range s.Events() {
		switch e.Type {
		case "step_start", "reasoning":
			if cur == nil {
				r.setPresenceOnce(b, Presence{State: "thinking"})
			}
		case "text":
			if e.Text == "" {
				continue
			}
			if cur == nil {
				cur = &store.Message{BotID: b.ID, Role: "bot", RunID: &runID}
				if err := r.store.AddMessage(context.WithoutCancel(ctx), cur, nil); err != nil {
					r.log.Error("add message", "err", err)
					continue
				}
				r.hub.Publish(b.SpaceID, Event{Type: "message", BotID: b.ID, Data: cur})
				r.setPresence(b, Presence{State: "working", Detail: "Writing"})
			}
			text.WriteString(e.Text)
			r.hub.Publish(b.SpaceID, Event{Type: "delta", BotID: b.ID, Data: map[string]string{"id": cur.ID, "text": e.Text}})
		case "step_end":
			flush()
		case "tool_call":
			flush()
			if e.ToolCall != nil {
				r.setPresence(b, Presence{State: "working", Detail: toolLabel(e.ToolCall.Name)})
			}
		case "interrupt":
			r.setPresence(b, Presence{State: "waiting", Detail: "Needs you"})
		}
	}
	flush()
	res, err := s.Result()
	tokens := 0
	if res != nil {
		tokens = res.Usage.TotalTokens
	}
	switch {
	case errors.Is(ctx.Err(), context.Canceled):
		_ = r.store.FinishRun(context.Background(), runID, "stopped", "", tokens)
		r.event(b, "Stopped", map[string]any{"kind": "stopped"})
		r.setPresence(b, Presence{State: "idle"})
	case err != nil:
		_ = r.store.FinishRun(context.Background(), runID, "error", err.Error(), tokens)
		r.botError(b, runID, friendlyError(model, err))
	default:
		_ = r.store.FinishRun(context.Background(), runID, "done", "", tokens)
		r.setPresence(b, Presence{State: "done"})
		go func() {
			time.Sleep(2500 * time.Millisecond)
			r.mu.Lock()
			still := r.presence[b.ID].State == "done"
			r.mu.Unlock()
			if still {
				r.setPresence(b, Presence{State: "idle"})
			}
		}()
	}
}

// setPresenceOnce avoids re-publishing an unchanged state per token.
func (r *Runtime) setPresenceOnce(b *store.Bot, p Presence) {
	r.mu.Lock()
	same := r.presence[b.ID].State == p.State && r.presence[b.ID].Detail == p.Detail
	r.mu.Unlock()
	if !same {
		r.setPresence(b, p)
	}
}

func (r *Runtime) event(b *store.Bot, text string, data map[string]any) {
	m := &store.Message{BotID: b.ID, Role: "event", Content: text, Data: data}
	if err := r.store.AddMessage(context.Background(), m, nil); err == nil {
		r.hub.Publish(b.SpaceID, Event{Type: "message", BotID: b.ID, Data: m})
	}
}

func (r *Runtime) botError(b *store.Bot, runID, msg string) {
	r.event(b, msg, map[string]any{"kind": "error"})
	r.setPresence(b, Presence{State: "blocked", Detail: "Something went wrong"})
}

func friendlyError(model string, err error) string {
	s := sdk.Redact(err.Error())
	switch {
	case strings.Contains(s, "connection refused") && strings.HasPrefix(model, "ollama:"):
		return "I can't reach Ollama. Is it running? (`ollama serve`)"
	case strings.Contains(s, "not found") && strings.HasPrefix(model, "ollama:"):
		return fmt.Sprintf("The model %s isn't installed. Run `ollama pull %s`.", strings.TrimPrefix(model, "ollama:"), strings.TrimPrefix(model, "ollama:"))
	case errors.Is(err, eipagent.ErrThreadBusy):
		return "I was busy with another task. Send that again?"
	}
	if len(s) > 300 {
		s = s[:300] + "…"
	}
	return "I hit a snag: " + s
}
