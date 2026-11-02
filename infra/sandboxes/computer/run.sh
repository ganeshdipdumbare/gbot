#!/bin/bash
# Runs one command for the bot, mirroring it into the Terminal so the
# person watching sees exactly what happens (the Terminal opens if needed).
log=/tmp/gbot/activity.log
cd "$HOME"
(DISPLAY=:1 setsid gbot-terminal >/dev/null 2>&1 < /dev/null &)
printf '\n\033[38;5;141m❯\033[0m \033[1m%s\033[0m\n' "$GBOT_CMD" >> "$log"
bash -lc "$GBOT_CMD" > >(tee -a "$log") 2> >(tee -a "$log" >&2)
code=$?
wait
if [ "$code" -ne 0 ]; then printf '\033[38;5;203m✕ exit %s\033[0m\n' "$code" >> "$log"; fi
exit "$code"
