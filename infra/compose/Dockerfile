# syntax=docker/dockerfile:1
# Build context: the gbot repo root. eipagent is a private module, so the
# Go stage reads a GitHub token from the gh_token build secret.
FROM node:24-alpine AS web
RUN corepack enable
WORKDIR /src
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY apps/web/package.json apps/web/
COPY packages/characters/package.json packages/characters/
COPY packages/ui-tokens/package.json packages/ui-tokens/
RUN pnpm install --frozen-lockfile
COPY apps/web apps/web
COPY packages packages
RUN pnpm --filter @web build

FROM golang:1.27-alpine AS server
WORKDIR /src
RUN apk add --no-cache git
ENV GOPRIVATE=github.com/ganeshdipdumbare/*
COPY server/go.mod server/go.sum server/
RUN --mount=type=secret,id=gh_token,required=true cd server && \
    git config --global url."https://x-access-token:$(cat /run/secrets/gh_token)@github.com/".insteadOf "https://github.com/" && \
    go mod download && git config --global --unset-all url."https://x-access-token:$(cat /run/secrets/gh_token)@github.com/".insteadOf
COPY server server
COPY --from=web /src/server/internal/web/dist server/internal/web/dist
RUN cd server && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /gbot ./cmd/gbot

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=server /gbot /gbot
USER nonroot
EXPOSE 8080
ENTRYPOINT ["/gbot", "serve"]
