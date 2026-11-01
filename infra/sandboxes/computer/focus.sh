#!/bin/bash
# gbot-focus CLASS COMMAND...: bring a window of CLASS to the front, or
# start COMMAND when there isn't one (like Rakazo's focus-or-launch).
class="$1"; shift
wid=$(xdotool search --onlyvisible --class "$class" 2>/dev/null | tail -1)
if [ -n "$wid" ]; then
  xdotool windowmap "$wid" windowactivate "$wid" 2>/dev/null
  exit 0
fi
exec "$@"
