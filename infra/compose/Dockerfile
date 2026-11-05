# syntax=docker/dockerfile:1
# Build context: the gbot repo root.
FROM node:24-alpine AS web
RUN corepack enable
WORKDIR /src
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY apps/web/package.json apps/web/
COPY packages/characters/package.json packages/characters/
COPY packages/ui-tokens/package.json packages/ui-tokens/
COPY apps/mobile/package.json apps/mobile/
COPY apps/desktop/package.json apps/desktop/
RUN pnpm install --frozen-lockfile
COPY apps/web apps/web
COPY packages packages
RUN pnpm --filter @gbot/web build

FROM golang:1.27-alpine AS server
WORKDIR /src
COPY server/go.mod server/go.sum server/
RUN cd server && go mod download
COPY server server
COPY --from=web /src/server/internal/web/dist server/internal/web/dist
RUN cd server && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /gbot ./cmd/gbot

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=server /gbot /gbot
USER nonroot
EXPOSE 8080
ENTRYPOINT ["/gbot", "serve"]
