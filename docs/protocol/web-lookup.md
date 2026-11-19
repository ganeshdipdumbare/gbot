# Web lookups

Research used to go through the browser tool: open a page, snapshot it, click,
snapshot again. A question a search answers took many slow steps, and on the
phone every `web_fetch` asked for approval first.

Two read-only tools now answer most lookups in one step:

- `web_search(query)`: up to 8 hits as title, https link and snippet, in about
  a second. Several searches can run in the same step.
- `web_fetch(url)`: one https page as text, led by its `Title:` and its main
  picture (`Image:`, the page's `og:image`). The bot passes that picture to
  `show_results`, so each card has its own photo.

The system prompt tells the bot to search first and keep the browser for
sign-ins, forms and pages that need clicks.

## Rules

- Links are https only. Search links are unwrapped from Bing's tracker. The
  server follows up to four redirects and only to https; the phone follows
  redirects too but never from https to http.
- `web_fetch` reads only a page a search returned in the same run, or an https
  link the person wrote in their own message (a chat message: not one from outside
  text, a teammate bot, Slack, a routine or a group hand-off). It asks no approval, so a page the bot invented, such as one an
  injected instruction wrote with private text in its address, is refused with
  a pointer to `browser_navigate`, which asks.
- Server: requests go through the guarded client (link-local and cloud
  metadata addresses refused; loopback and private networks too on hosted
  installs, where `BlockPrivate` is set), 12 s timeout, 1 MB read, 12,000
  characters of text, no kept-alive connections.
- What they return is outside text. On the server the run is tainted after
  them, the same as after a browser page, so a risky tool still asks first.
  The phone does not track taint; the fetch limit above is its protection.
- No approval card: they only read, and the person sees the step in the
  activity line.
- Search uses Bing's public results page; no key is needed.

## Where

- Server: `server/internal/runs/websearch.go`, registered in `tools.go`.
- Phone: `WebLookup.kt`, used by `web_search` and `web_fetch` in `LocalTools.kt`.
