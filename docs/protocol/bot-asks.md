# Bots asking bots

How one bot gets work from another. The server (`server/internal/runs/asks.go`)
and the phone (`apps/android/.../local/Asks.kt`) implement the same rules and
are tested against the same cases in `bot-asks.fixtures.json`.

## Ask or tell

`message_bot(to, message, expect)`:

- **ask** (default): the sender needs an answer. The receiver must answer.
- **tell**: passing something on. The receiver runs (it may act on it), but
  nothing comes back and nothing wakes the sender.

## Rounds

Every ask a bot sends during one run belongs to one **round**. The round is
open while that run lasts. When it has closed and every ask in it has ended,
the sender gets **one** run (trigger `replies`) whose prompt holds every answer
(`joinPrompt`). A round that hasn't finished within the round timeout is
joined anyway: the asks still out are marked `expired`.

Tells never belong to a round.

## The card

Each message is two `role: "event"` messages, one in each bot's chat, with
the same data:

| key | meaning |
|---|---|
| `kind` | `"botmsg"` |
| `ask` | id shared by the two cards of one message |
| `from`, `to` | bot ids |
| `message` | what was sent |
| `expect` | `"ask"` or `"tell"` |
| `round` | the round id (asks only) |
| `root` | the person's request the chain started from |
| `depth` | the receiver's depth: 1 for a bot the person's bot asked |
| `status` | `sent` → `working` → `replied` / `delivered` / `failed` / `needs_you` / `expired` / `cancelled` |
| `reply` | the receiver's answer, or why there isn't one |
| `joined` | on the sender's card: the round's answers have gone back |
| `tookMs` | on the sender's card, once joined: how long the round took |

`delivered` means the message was taken in and nothing needed to go back
(a tell, or `NO_REPLY`).

The card is about the message, not the answer: in the sender's chat it reads
"Asked Nori" / "Sent", in the receiver's "Message from Pip" / "Received", and
opening it shows what was sent. `reply` is kept for the sender's next prompt
but is never drawn — the asking bot says what matters in its own chat when the
answers come back (`joinPrompt` tells it so).

## Limits

- Depth: a bot at depth 2 or deeper can't message anyone (person → A → B → C,
  and C stops).
- At most 6 messages (asks and tells) for each request from the person.
- A bot answering an ask can't message the bot that asked it: its answer is
  the reply.
- Round timeout: 10 minutes on the server, 20 on the phone (runs wait in line
  there).

## Recovery

On start, a card still `sent` or `working` is put back in line, and a round
whose asks have all ended but that was never joined is joined. The cards are
the record; nothing else needs saving.

## Trace

Each request gets a trace: runs, model calls, messages, deepest depth and
wall-clock time. It is logged when a round is joined, as
`trace root=… runs=… asks=… depth=… ms=…`.
