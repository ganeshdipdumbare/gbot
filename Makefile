# gbot: everything runs on your machine.
COMPOSE := docker compose -f infra/compose/docker-compose.yml

THREE := 0.187.0

.PHONY: db web server computer run dev test desktop android site site-three clean

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

site:          ## preview the landing page on http://127.0.0.1:4173
	python3 -m http.server 4173 --bind 127.0.0.1 --directory site

site-three:    ## rebundle the parts of three.js the landing page uses
	@tmp=$${TMPDIR:-/tmp}/gbot-three-$(THREE) && mkdir -p $$tmp && npm install --silent --no-audit --no-fund --prefix $$tmp three@$(THREE) esbuild && \
	$$tmp/node_modules/.bin/esbuild site/assets/vendor/three.entry.js --bundle --minify --format=esm \
		--alias:three=$$tmp/node_modules/three/build/three.module.js \
		--banner:js='/* three.js $(THREE), MIT, https://threejs.org. Built by make site-three. */' \
		--outfile=site/assets/vendor/three-$(THREE).min.js

clean:
	rm -rf bin server/internal/web/dist/assets server/internal/web/dist/index.html*
