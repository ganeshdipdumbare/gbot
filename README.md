# gbot

Cute, persistent AI teammates that run entirely on your own machine.

Each bot is a simple, expressive character with its own name, memory, one
continuous chat, and its own computer. Bots remember what you tell them,
show what they're doing through their eyes and motion (thinking, working,
needs you, stuck, done), and keep working while you keep chatting.

- **Approvals and questions.** Bots ask before risky steps with a card in the
  chat (Ask, Auto or Never ask, plus allow/block lists). They ask you
  questions the same way. Cards never block the chat.
- **A computer per bot.** A private Linux desktop (browser, terminal, files, and
  an Activity log of everything the bot runs) in its own microVM via
  [microsandbox](https://github.com/superradcompany/microsandbox), or a Docker
  container. No network unless you allow it. Watch it live beside the chat, or
  take control when it needs you (sign-ins, codes).
- **Routines.** Prompts a bot runs on a schedule or when a webhook fires.
  Ask for one in chat, or set it up in the bot's panel.
- **Integrations.** Remote MCP servers (tokens encrypted at rest, scoped to your
  account) and skills you write once and use anywhere with `/`.
- **Group chats.** Up to six bots in one chat. The first leads, `@mentions`
  bring others in, and bots hand work to each other.
- **Voice.** Dictation and read-aloud, local by default.
- **Everywhere.** Web, a 4 MB desktop app (Tauri), and iOS/Android (Expo).

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

Build the bots' computer image once (optional, about 1 GB, shared by every
bot). For lightweight microVM computers (boot in under a second, about 75 MB
of memory at idle), install microsandbox first; `make computer` loads the
image into it:

```bash
brew install superradcompany/tap/microsandbox
```

```bash
make computer
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
| `GBOT_COMPUTER` | `on` | `off` disables bot computers |
| `GBOT_COMPUTER_BACKEND` | auto | `microsandbox` or `docker`; auto prefers microsandbox when `msb` is installed |
| `GBOT_COMPUTER_IMAGE` | `gbot-computer:latest` | image for bot computers |
| `GBOT_KEY_FILE` | `~/.config/gbot/secret.key` | key that encrypts integration tokens (or set `GBOT_SECRET_KEY`) |
| `GBOT_STT_URL`, `GBOT_TTS_URL` | | OpenAI-compatible speech servers (e.g. faster-whisper, Kokoro), or `openai` |
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`, `GROQ_API_KEY`, `OPENROUTER_API_KEY` | | optional cloud models |

## Layout

```
server/               Go: API, auth, store, agent runtime (eipagent), computers, routines
apps/web/             React 19 + Vite + Tailwind + Motion
apps/desktop/         Tauri 2 shell with native notifications
apps/mobile/          Expo (React Native) app
packages/characters/  the gbot character rig: seeded traits, moods, animation
packages/ui-tokens/   design tokens shared by every surface
infra/compose/        Postgres (+ optional Ollama and app image)
infra/sandboxes/      the bots' computer image
```

## Tests

```bash
make test
```

The Go tests run the whole API against a throwaway database with
eipagent's offline mock model, so they need no network or keys.

## Desktop and mobile

```bash
make desktop   # builds apps/desktop/src-tauri/target/release/bundle/macos/gbot.app
make mobile    # opens the app in the iOS simulator via Expo Go
```

Both connect to your gbot server; the phone needs its address on your network.

## Security notes

- Bots run one tool call at a time through a guard that applies the owner's
  approval mode and allow/block lists. Once outside text (a webhook, a web page,
  an MCP result) enters a run, changes always ask first, even in Auto.
- Computers are hardware-isolated microVMs (or containers with all capabilities
  dropped and no new privileges), with CPU/memory limits and no network by
  default. gbot reaches them only by running commands inside them.
  Takeover input is accepted only while you hold control.
- Webhooks need the routine's secret (bearer token or HMAC signature).
- MCP tokens are sealed with AES-256-GCM and redacted from logs; each account
  only ever sees its own servers' tools.
