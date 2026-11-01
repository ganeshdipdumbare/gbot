#!/bin/bash
# Starts the desktop: a 1280x800 screen, Fluxbox, then the browser in
# front. The file manager and a shell are open behind it (and on the
# taskbar); Activity keeps a log of everything the bot runs.
set -u
export DISPLAY=:1 HOME=/home/gbot LANG=C.UTF-8 LC_ALL=C.UTF-8
mkdir -p /tmp/gbot "$HOME/.fluxbox" "$HOME/.config/gtk-3.0"
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
touch /tmp/gbot/activity.log
printf '[Settings]\ngtk-application-prefer-dark-theme=1\ngtk-font-name=DejaVu Sans 11\n' > "$HOME/.config/gtk-3.0/settings.ini"

if command -v dbus-launch >/dev/null 2>&1; then
  eval "$(dbus-launch --sh-syntax)" || true
fi

Xvfb :1 -screen 0 1280x800x24 -nolisten tcp -noreset >/tmp/gbot/xvfb.log 2>&1 &
for _ in $(seq 1 100); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.05; done

xsetroot -solid '#111113'
cp /etc/gbot/fluxbox/* "$HOME/.fluxbox/"
fluxbox -rc "$HOME/.fluxbox/init" >/tmp/gbot/fluxbox.log 2>&1 &

printf '\033[38;5;141m●\033[0m %s\n\033[2m%s\033[0m\n\n' "Computer ready" "$(date '+%a %d %b, %H:%M') · $(nproc) CPUs · $(free -m | awk '/Mem/{print $2}') MB" >> /tmp/gbot/activity.log
XT=(-u8 -fa 'DejaVu Sans Mono' -fs 11 -bg '#111113' -fg '#E8E8EA' -cr '#8B5CF6' -b 14 +sb -sl 4000)
echo "XT=(${XT[*]@Q})" > /tmp/gbot/xterm.env
xterm "${XT[@]}" -iconic -class GbotActivity -title "Activity" -geometry 100x30+48+40 \
  -e bash -c 'tail -n 200 -F /tmp/gbot/activity.log' &

pcmanfm --new-win "$HOME" >/tmp/gbot/files.log 2>&1 &
gbot-browser >/tmp/gbot/browser.log 2>&1 &

# The browser is the screen you see. If it never appears, leave the shell up.
browser_up=0
for _ in $(seq 1 40); do
  if xdotool search --class GbotBrowser >/dev/null 2>&1 || xdotool search --class Chromium >/dev/null 2>&1; then
    browser_up=1
    break
  fi
  sleep 0.25
done
if [ "$browser_up" -eq 1 ]; then
  wid=$(xdotool search --class GbotBrowser 2>/dev/null | head -n 1 || true)
  [ -n "$wid" ] && xdotool windowactivate "$wid" >/dev/null 2>&1 || true
  # The file manager maps around the same time and can cover the browser.
  ( sleep 2
    wid=$(xdotool search --class GbotBrowser 2>/dev/null | head -n 1 || true)
    [ -n "$wid" ] && xdotool windowactivate "$wid" >/dev/null 2>&1 || true
  ) &
else
  echo "browser failed to start" >> /tmp/gbot/browser.log
  xdotool search --class GbotActivity windowmap windowactivate >/dev/null 2>&1 || true
fi

wait
