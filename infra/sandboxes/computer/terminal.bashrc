# The Terminal's shell: the person's prompt, with the bot's activity
# streaming in above it.
[ -f "$HOME/.bashrc" ] && . "$HOME/.bashrc"
PS1='\[\e[38;5;141m\]❯\[\e[0m\] \[\e[2m\]\w\[\e[0m\] '
tail -n 80 /tmp/gbot/activity.log 2>/dev/null
tail -n 0 -F /tmp/gbot/activity.log 2>/dev/null &
disown
