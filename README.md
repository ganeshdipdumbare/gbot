# gbot

Cute, persistent AI teammates that run entirely on your own machine.

Each bot is a little character with its own name, look, memory and one
continuous chat. Bots remember what you tell them, show what they're doing
through their expressions (thinking, working, needs you, stuck, done), and
keep working while you keep chatting.

- **Local first.** Defaults to models on your machine through
  [Ollama](https://ollama.com) or any OpenAI-compatible local server. No
  API keys, no accounts elsewhere, no telemetry. Cloud providers are optional.
- **Built on [eipagent](https://github.com/ganeshdipdumbare/eipagent).** One
  shared agent in the secure preset: Postgres-backed threads and memory,
  retries, limits, summarization, and per-plugin secret grants.
- **Fast.** One Go binary serves the API, live updates (SSE) and the
  precompressed web app (about 130 KB of JS, brotli).
- **Secure by default.** argon2id passwords, hashed session tokens,
  CSRF and origin checks, strict CSP, rate limits, and spaces as the
  authorization boundary on every query.

## Run it

You need Docker (for Postgres), Go 1.27+, Node 24 with pnpm, and Ollama with
a tool-calling model:

```bash
ollama pull qwen3.5:4b
```

gbot uses [eipagent](https://github.com/ganeshdipdumbare/eipagent) v0.3.0, a
private Go module. Let Go fetch it with your GitHub login:

```bash
gh auth setup-git
```

```bash
go env -w GOPRIVATE='github.com/ganeshdipdumbare/*'
```

Then build and start everything:

```bash
make run
```

Open http://127.0.0.1:8080 and hatch your first bot.

For development with hot reload, run `make dev` and open http://127.0.0.1:5173.

### Configuration

| Variable | Default | |
|---|---|---|
| `GBOT_DATABASE_URL` | local compose Postgres | Postgres connection string |
| `GBOT_ADDR` | `127.0.0.1:8080` | listen address |
| `OLLAMA_HOST` | `http://127.0.0.1:11434` | local model server |
| `GBOT_SIGNUP` | `open` | `closed` allows only the first account |
| `GBOT_PUBLIC_URL` | | set to an `https://` URL to enable secure cookies and HSTS |
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`, `GROQ_API_KEY`, `OPENROUTER_API_KEY` | | optional cloud models |

## Layout

```
server/               Go: API, auth, store, agent runtime (eipagent)
apps/web/             React 19 + Vite + Tailwind + Motion
packages/characters/  the gbot character rig: seeded traits, moods, animation
packages/ui-tokens/   design tokens shared by every surface
infra/compose/        Postgres (+ optional Ollama and app image)
```

## Tests

```bash
make test
```

The Go tests run the whole API against a throwaway database with
eipagent's offline mock model, so they need no network or keys.

## Roadmap

Approvals and question cards, each bot's computer (Docker sandbox with
preview and takeover), routines, integrations (MCP, skills), group chats,
desktop (Tauri), mobile (Expo), and voice.
