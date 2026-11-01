#!/bin/bash
# gbot-browser [URL]: with a URL, opens it in the bot's browser (a new tab
# in front, maximized like on Rakazo); without one, brings the browser
# forward, or starts it on the start page.
flags=(--class=GbotBrowser --no-sandbox --test-type --no-first-run --no-default-browser-check
  --disable-dev-shm-usage --disable-features=Translate --password-store=basic
  --user-data-dir="$HOME/.browser" --start-maximized --hide-crash-restore-bubble --disable-session-crashed-bubble)
# Computers are stopped, not shut down, so tell Chromium the last exit was clean.
prefs="$HOME/.browser/Default/Preferences"
[ -f "$prefs" ] && sed -i 's/"exit_type":"Crashed"/"exit_type":"Normal"/; s/"exited_cleanly":false/"exited_cleanly":true/' "$prefs"
if [ -z "${1:-}" ]; then
  exec gbot-focus GbotBrowser chromium "${flags[@]}" file:///usr/local/share/gbot/start.html >>/tmp/gbot/browser.log 2>&1
fi
printf '\n\033[38;5;111m◍\033[0m Opening %s\n' "$1" >> /tmp/gbot/activity.log
chromium "${flags[@]}" "$1" >>/tmp/gbot/browser.log 2>&1 &
# Bring it forward once the tab is up.
for _ in $(seq 1 20); do
  wid=$(xdotool search --onlyvisible --class GbotBrowser 2>/dev/null | tail -1)
  [ -n "$wid" ] && { xdotool windowactivate "$wid" 2>/dev/null; break; }
  sleep 0.25
done
wait
