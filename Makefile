# gbot: everything runs on your machine.
COMPOSE := docker compose -f infra/compose/docker-compose.yml

.PHONY: db web server computer run dev test desktop android clean

db:            ## start local Postgres
	$(COMPOSE) up -d postgres

web:           ## build the web app into the server binary
	pnpm install && pnpm build:web

server:        ## build bin/gbot
	cd server && go build -trimpath -o ../bin/gbot ./cmd/gbot

computer:      ## build the bots' computer image, and load it into microsandbox if installed
	docker build -t gbot-computer:latest infra/sandboxes/computer
	@if command -v msb >/dev/null; then docker save gbot-computer:latest | msb load -t gbot-computer:latest; fi

run: db web server   ## build everything and start gbot on http://127.0.0.1:8080
	./bin/gbot serve

dev: db        ## API on :8080 plus Vite with hot reload on :5173
	(cd server && go run ./cmd/gbot serve) & pnpm dev:web

test: db       ## Go tests (offline mock model) and package tests
	cd server && go test -race ./...
	pnpm --filter @gbot/characters test
	pnpm --filter @gbot/web typecheck

desktop:       ## build the desktop app (Tauri)
	pnpm --filter @gbot/desktop build

android:       ## build the native Android app (Kotlin + Compose)
	cd apps/android && ./gradlew assembleRelease
	@echo apps/android/app/build/outputs/apk/release/app-release.apk

clean:
	rm -rf bin server/internal/web/dist/assets server/internal/web/dist/index.html*
