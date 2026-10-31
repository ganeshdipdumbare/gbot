#!/bin/sh
# Start a 1280x800 virtual screen with a light window manager, then idle.
set -e
rm -f /tmp/.X1-lock
Xvfb :1 -screen 0 1280x800x24 -nolisten tcp &
for i in $(seq 1 50); do xdpyinfo -display :1 >/dev/null 2>&1 && break; sleep 0.1; done 2>/dev/null || true
openbox >/dev/null 2>&1 &
# A calm solid background matching gbot's surface color.
sleep 0.3; xsetroot -solid '#F4EFE8' 2>/dev/null || true
exec sleep infinity
