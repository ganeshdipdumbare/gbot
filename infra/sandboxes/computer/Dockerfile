# The bot's computer: a small Linux desktop with a browser.
# Runs as an unprivileged user; gbot starts it with no network unless the
# bot's owner turns network on, and drives it only through `docker exec`.
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb xdotool imagemagick openbox chromium x11-utils x11-xserver-utils \
      fonts-dejavu-core fonts-noto-color-emoji fonts-inter \
      ca-certificates curl git python3 jq procps \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -u 1000 -s /bin/bash gbot

COPY start.sh /usr/local/bin/gbot-desktop
RUN chmod 755 /usr/local/bin/gbot-desktop

USER gbot
WORKDIR /home/gbot
ENV DISPLAY=:1 HOME=/home/gbot
CMD ["gbot-desktop"]
