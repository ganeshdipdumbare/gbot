# Result cards

When a tool hands back a list of records (hotels, products, places), gbot
shows them in the chat as cards: a thumbnail, a title, a subtitle, a few
label/value lines, and a link. The model still reads the tool's text. The
cards are a second view for the person, built only from the structured data.

## When a card is made

- The tool's answer is a JSON list of at least two objects, or a JSON object
  holding such a list (under any key, the first one found, in sorted key order).
- Each record needs a `title`, `name` or `label`. Records without one are
  skipped. At most 12 cards are kept, and at least two must remain.
- The server reads `structuredContent` when the tool sends it, otherwise a
  JSON text answer. Plain-text answers stay plain text.

## The message

A chat row with `role: "event"` and `data.kind: "results"`:

```json
{
  "kind": "results",
  "from": "Hotels",
  "title": "Hotels, Darjeeling, 9-13 Dec",
  "items": [
    {
      "title": "Echo Hotel and Spa",
      "subtitle": "The Mall",
      "image": "<blob id>",
      "mime": "image/jpeg",
      "link": "https://example.com/echo",
      "fields": [{ "label": "Rating", "value": "9.6" }]
    }
  ]
}
```

- `image` is a blob id the server fetched and keeps (`/api/blobs/{id}`). The
  phone keeps the picture as a file instead and writes `file` in its place.
  Clients never load a picture from the site the tool named.
- `link` is only ever an absolute `https` URL. Anything else is dropped.
- `fields` are the record's other scalar values, sorted by key, humanized
  ("price_per_night" becomes "Price per night"), at most 6. Nested objects and
  lists are not shown.
- Text is clipped to 160 characters.

## Clients

- Web: `results` rows render as `ResultCard` in `apps/web/src/components/Chat.tsx`.
- Android: `ResultsRow` in `apps/android/app/src/main/java/dev/gbot/app/ui/chat/Results.kt`.
- Phone without the server: `apps/android/app/src/main/java/dev/gbot/app/local/Results.kt`
  mirrors the server rules and is used from `LocalBrain`.

A client that does not know `results` shows nothing extra; the model's text
answer is still there.

## Not included

- Action buttons such as "Refresh prices" that re-run a tool. A card only
  links out. Anything that changes state or spends money stays a separate,
  confirmed step.
