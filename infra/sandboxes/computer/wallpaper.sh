#!/bin/bash
# gbot-wallpaper: lays the wallpaper for the time of day (lighter in the
# morning, darker at night) and keeps it in step every few minutes.
dir=/usr/local/share/gbot
paint() {
  h=$((10#$(date +%H)))
  if [ "$h" -ge 6 ] && [ "$h" -lt 11 ]; then v=day
  elif [ "$h" -ge 11 ] && [ "$h" -lt 18 ]; then v=noon
  else v=night; fi
  [ "$v" = "${current:-}" ] && return
  hsetroot -fill "$dir/wallpaper-$v.png" >/dev/null 2>&1 || xsetroot -solid '#141414'
  current=$v
}
paint
[ "${1:-}" = "--once" ] && exit 0
while sleep 300; do paint; done
