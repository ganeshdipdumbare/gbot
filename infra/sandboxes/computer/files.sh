#!/bin/bash
# gbot-files: the bot's home folder in the file manager, focused if open.
exec gbot-focus Pcmanfm pcmanfm --new-win "$HOME" >>/tmp/gbot/files.log 2>&1
