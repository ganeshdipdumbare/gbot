# Result cards

When the bot has a list to compare (hotels, products, places), it hands the
list to the `show_results` tool, and gbot shows the list in the chat as cards:
a thumbnail, a title, a subtitle, a few label/value lines, and a link. The
bot's reply text still says what matters. The cards are a second view for the
person.

Only the bot's own `show_results` call makes cards. A tool's answer never does
by itself, so a tool that returns a list of records stays a text answer unless
the bot chooses to show it.

## The tool

`show_results` takes:

- `title` (optional): a heading, e.g. "Hotels in Darjeeling, 9-13 Dec".
- `items`: 2 to 12 records. Each has `title` (required), and optionally
  `subtitle`, `link`, `image` and `fields` (up to 6 `{label, value}` lines).

The bot must take each item from a tool answer it has read. A card with fewer
than two titled items is refused, so a single result is just a sentence.

## Rules

- `link` is only ever an absolute `https` URL. Anything else is dropped; the
  card is still shown.
- `image` must be `https`. gbot fetches it through the guarded client (no
  private or loopback addresses, size and type limits) and keeps it as a blob.
  Clients never load a picture from the site the bot named.
- Text is clipped to 160 characters. A field with an empty label or value is
  skipped.

## The message

A chat row with `role: "event"` and `data.kind: "results"`:

```json
{
  "kind": "results",
  "title": "Hotels, Darjeeling, 9-13 Dec",
  "items": [
    {
      "title": "Echo Hotel and Spa",
      "subtitle": "The Mall",
      "image": "<blob id>",
      "mime": "image/jpeg",
      "link": "https://example.com/echo",
      "fields": [{ "label": "Price", "value": "$33.50 a night" }]
    }
  ]
}
```

- On the server, `image` is a blob id served at `/api/blobs/{id}`.
- On the phone, the picture is kept as a file and `file` is written in its
  place of `image`.

## Clients

- Server: `show_results` in `server/internal/runs/tools.go`, built in
  `server/internal/runs/results.go`.
- Web: `results` rows render as `ResultCard` in `apps/web/src/components/Chat.tsx`.
- Android: `ResultsRow` in `apps/android/app/src/main/java/dev/gbot/app/ui/chat/Results.kt`.
- Phone without the server: the same tool in `LocalTools.kt` and `LocalBrain.kt`,
  with the card rules in `apps/android/app/src/main/java/dev/gbot/app/local/Results.kt`.

A client that does not know `results` shows nothing extra; the bot's text
answer is still there.

## Not included

- Action buttons such as "Refresh prices" that re-run a tool. A card only
  links out. Anything that changes state or spends money stays a separate,
  confirmed step.
- A per-bot switch to turn cards off.
