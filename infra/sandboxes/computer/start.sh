#!/bin/bash
# Starts the desktop: a 1280x800 screen with the wallpaper, Fluxbox and
# the dock. No apps are open; the bot opens the browser or the Terminal
# when it needs one, and the person can from the dock.
set -u
export DISPLAY=:1 HOME=/home/gbot LANG=C.UTF-8 LC_ALL=C.UTF-8 XCURSOR_THEME=Adwaita XCURSOR_SIZE=24
mkdir -p /tmp/gbot "$HOME/.fluxbox" "$HOME/.config/gtk-3.0"
# python-xlib (pyautogui) insists on an Xauthority file, even with no auth.
touch "$HOME/.Xauthority"
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
touch /tmp/gbot/activity.log
printf '[Settings]\ngtk-application-prefer-dark-theme=1\ngtk-font-name=Inter 10\ngtk-cursor-theme-name=Adwaita\n' > "$HOME/.config/gtk-3.0/settings.ini"

Xvfb :1 -screen 0 1280x800x24 -nolisten tcp -noreset >/tmp/gbot/xvfb.log 2>&1 &
for _ in $(seq 1 100); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.05; done

xsetroot -cursor_name left_ptr
gbot-wallpaper --once  # before the dock, which borrows it for its glass
gbot-wallpaper >/tmp/gbot/wallpaper.log 2>&1 &
cp -r /etc/gbot/fluxbox/* "$HOME/.fluxbox/"
fluxbox -rc "$HOME/.fluxbox/init" >/tmp/gbot/fluxbox.log 2>&1 &
for _ in $(seq 1 40); do xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q window && break; sleep 0.05; done
tint2 -c /usr/local/share/gbot/dock/tint2rc >/tmp/gbot/dock.log 2>&1 &

printf '\033[38;5;141m●\033[0m %s\n\033[2m%s\033[0m\n\n' "Computer ready" "$(date '+%a %d %b, %H:%M') · $(nproc) CPUs · $(free -m | awk '/Mem/{print $2}') MB" >> /tmp/gbot/activity.log

wait
