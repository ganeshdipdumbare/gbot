#!/bin/bash
# gbot-terminal: a dark shell, focused if it's already open.
. /tmp/gbot/xterm.env
exec gbot-focus GbotTerminal xterm "${XT[@]}" -class GbotTerminal -title Terminal -geometry 100x30+120+80
