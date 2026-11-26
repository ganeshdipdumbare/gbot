# The bot's computer: a small Linux desktop with a wallpaper, a dock and
# two apps (a browser and a terminal) that open when the bot needs them.
# It runs as an unprivileged user with no network unless the bot's owner turns network on, and gbot drives
# it only by running commands inside it (Docker exec or microsandbox exec).
FROM debian:bookworm-slim AS capture
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev libx11-dev libjpeg62-turbo-dev libpng-dev \
    && rm -rf /var/lib/apt/lists/*
COPY capture.c /src/capture.c
RUN gcc -O2 -o /gbot-capture /src/capture.c -lX11 -ljpeg -lpng

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb fluxbox tint2 hsetroot xterm xdotool x11-utils x11-xserver-utils libjpeg62-turbo libpng16-16 \
      chromium fonts-dejavu-core fonts-liberation fonts-noto-color-emoji fonts-inter fonts-jetbrains-mono \
      adwaita-icon-theme librsvg2-common tzdata \
      python3 python3-pip python3-tk python3-xlib wmctrl ca-certificates curl git jq procps unzip less \
    && rm -rf /var/lib/apt/lists/* /usr/share/doc /usr/share/man \
    && useradd -m -u 1000 -s /bin/bash gbot \
    && mkdir -p /etc/gbot/fluxbox \
    && printf '#!/bin/sh\nexec gbot-wallpaper --once\n' > /usr/bin/fbsetbg && chmod 755 /usr/bin/fbsetbg \
    && ln -sfn /bin/true /usr/bin/xmessage \
    && mkdir -p /usr/local/share/gbot /etc/gtk-3.0 \
    && printf '[Settings]\ngtk-application-prefer-dark-theme=1\ngtk-font-name=Inter 10\ngtk-cursor-theme-name=Adwaita\n' > /etc/gtk-3.0/settings.ini

COPY wallpaper.py /tmp/wallpaper.py
RUN python3 /tmp/wallpaper.py /usr/local/share/gbot && rm /tmp/wallpaper.py

COPY --from=capture /gbot-capture /usr/local/bin/gbot-capture
COPY start.sh /usr/local/bin/gbot-desktop
COPY run.sh /usr/local/bin/gbot-run
COPY browser.sh /usr/local/bin/gbot-browser
COPY focus.sh /usr/local/bin/gbot-focus
COPY terminal.sh /usr/local/bin/gbot-terminal
COPY wallpaper.sh /usr/local/bin/gbot-wallpaper
COPY web.py /usr/local/bin/gbot-web
COPY fx.js /usr/local/share/gbot/fx.js
COPY terminal.bashrc /usr/local/share/gbot/terminal.bashrc
COPY dock/ /usr/local/share/gbot/dock/
COPY dock/icons/ /usr/local/share/gbot/icons/
COPY browser-policy.json /etc/chromium/policies/managed/gbot.json
COPY start.html /usr/local/share/gbot/start.html
COPY fluxbox/ /etc/gbot/fluxbox/
RUN pip3 install --break-system-packages --no-cache-dir playwright==1.64.1 pyautogui==0.9.54 \
    && rm -rf /root/.cache
RUN chmod 755 /usr/local/bin/gbot-*

USER gbot
WORKDIR /home/gbot
ENV DISPLAY=:1 HOME=/home/gbot LANG=C.UTF-8 LC_ALL=C.UTF-8
CMD ["/usr/local/bin/gbot-desktop"]
