# UI surfaces (`show_ui`, phone mode)

In phone mode the bot can draw a small screen in the chat with `show_ui`, built
from [A2UI](https://a2ui.org) parts and drawn natively by Google's A2UI Jetpack
Compose renderer (`androidx.a2ui.compose`, protocol v0.9). It replaces
`show_results` on the phone. The server still has `show_results`, and its
`results` rows are drawn the same way.

`show_ui` takes a `title` and one of:

- `items`: a list to compare, with the same fields and rules as
  [results.md](results.md). It becomes one Card per item.
- `components`: a flat A2UI component list, with optional `data` that inputs
  bind to with `{"path": "/key"}`.

## Catalog

The catalog is `https://gbot.dev/a2ui/catalogs/phone/v1`. It uses the basic
catalog's names and properties for these parts: Text, Image, Row, Column, List,
Card, Divider, Button, TextField, CheckBox, ChoicePicker and Slider. Anything
else is refused, and the reason goes back to the bot so it can fix the surface.

## Rules (`local/Surfaces.kt`)

- Only the parts above, with only their own properties. Every child a part
  names must exist, one part has the id `root`, and nothing may loop.
- An Image `url` must be an https address written out, one a tool gave or the
  person wrote. The phone fetches it once, keeps it, and the surface names the
  kept file (`gbot-file:`). A picture from anywhere else is left out.
- A Button either sends an `event` back to the bot or opens a written-out https
  link with `openUrl`. No other part may call `openUrl`. Only pure functions
  such as `formatString` and `required` may appear in values.

## Answers

A surface with an event button is a card, and the run waits for it the same
way it waits for `ask_user`. The tap is stored as the card's answer,
`{"event": name, "context": {...}}`, and the bot reads back
`The person tapped "book" with {...}`. The person can also tap Skip. Once
answered, the surface stays visible but no longer answers.
