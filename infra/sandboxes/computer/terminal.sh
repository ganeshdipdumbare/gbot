#!/bin/bash
# gbot-terminal: the computer's one shell. Everything the bot runs streams
# into it (from the activity log), and the person can type in it too.
# Brought forward if it's already open.
exec gbot-focus GbotTerminal xterm -u8 -fa 'JetBrains Mono' -fs 11 -bg '#141414' -fg '#E8E8EA' -cr '#A78BFA' \
  -b 16 +sb -sl 4000 -class GbotTerminal -title Terminal \
  -e bash --rcfile /usr/local/share/gbot/terminal.bashrc -i
