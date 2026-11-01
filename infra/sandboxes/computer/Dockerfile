# The bot's computer: a small Linux desktop with a browser, a file manager
# and a terminal, in the style of Rakazo's. It runs as an unprivileged user
# with no network unless the bot's owner turns network on, and gbot drives
# it only by running commands inside it (Docker exec or microsandbox exec).
FROM debian:bookworm-slim AS capture
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev libx11-dev libjpeg62-turbo-dev \
    && rm -rf /var/lib/apt/lists/*
COPY capture.c /src/capture.c
RUN gcc -O2 -o /gbot-capture /src/capture.c -lX11 -ljpeg

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb fluxbox xterm xdotool x11-utils x11-xserver-utils libjpeg62-turbo \
      chromium fonts-dejavu-core fonts-liberation fonts-noto-color-emoji \
      python3 ca-certificates curl git jq procps unzip less \
    && rm -rf /var/lib/apt/lists/* /usr/share/doc /usr/share/man \
    && useradd -m -u 1000 -s /bin/bash gbot \
    && mkdir -p /etc/gbot/fluxbox \
    && printf '#!/bin/sh\nexec xsetroot -solid "#111113"\n' > /usr/bin/fbsetbg && chmod 755 /usr/bin/fbsetbg \
    && ln -sfn /bin/true /usr/bin/xmessage

RUN apt-get update && apt-get install -y --no-install-recommends \
      pcmanfm dbus-x11 adwaita-icon-theme \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /usr/local/share/gbot /etc/gtk-3.0 \
    && printf '[Settings]\ngtk-application-prefer-dark-theme=1\ngtk-font-name=DejaVu Sans 11\n' > /etc/gtk-3.0/settings.ini

COPY --from=capture /gbot-capture /usr/local/bin/gbot-capture
COPY start.sh /usr/local/bin/gbot-desktop
COPY run.sh /usr/local/bin/gbot-run
COPY browser.sh /usr/local/bin/gbot-browser
COPY focus.sh /usr/local/bin/gbot-focus
COPY terminal.sh /usr/local/bin/gbot-terminal
COPY files.sh /usr/local/bin/gbot-files
COPY browser-policy.json /etc/chromium/policies/managed/gbot.json
COPY start.html /usr/local/share/gbot/start.html
COPY fluxbox/ /etc/gbot/fluxbox/
RUN chmod 755 /usr/local/bin/gbot-*

USER gbot
WORKDIR /home/gbot
ENV DISPLAY=:1 HOME=/home/gbot LANG=C.UTF-8 LC_ALL=C.UTF-8
CMD ["/usr/local/bin/gbot-desktop"]
