// Command gbot runs the gbot server.
//
//	gbot serve     start the server (runs migrations first)
//	gbot migrate   apply database migrations and exit
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"gbot/internal/api"
	"gbot/internal/models"
	"gbot/internal/runs"
	"gbot/internal/store"
)

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	level := slog.LevelInfo
	if os.Getenv("GBOT_DEBUG") != "" {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))
	if err := run(cmd, log); err != nil {
		log.Error(err.Error())
		os.Exit(1)
	}
}

func run(cmd string, log *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	dsn := env("GBOT_DATABASE_URL", "postgres://gbot:gbot-local@127.0.0.1:55432/gbot?sslmode=disable")
	st, err := store.Open(ctx, dsn)
	if err != nil {
		return fmt.Errorf("%w\nIs Postgres running? Try: docker compose -f infra/compose/docker-compose.yml up -d postgres", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		return err
	}
	switch cmd {
	case "migrate":
		log.Info("migrations applied")
		return nil
	case "serve":
	default:
		return fmt.Errorf("unknown command %q (serve, migrate)", cmd)
	}
	_ = st.FailInterruptedRuns(ctx)

	cat := models.NewCatalog(env("OLLAMA_HOST", "http://127.0.0.1:11434"))
	hub := runs.NewHub()
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 12}, st, hub, cat, log)
	if err != nil {
		return fmt.Errorf("agent runtime: %w", err)
	}
	defer rt.Close()

	addr := env("GBOT_ADDR", "127.0.0.1:8080")
	srv := &api.Server{
		Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log,
		SecureCookies: strings.HasPrefix(env("GBOT_PUBLIC_URL", ""), "https://"),
		AllowSignup:   env("GBOT_SIGNUP", "open") == "open",
	}
	hs := &http.Server{
		Addr:              addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		t := time.NewTicker(time.Hour)
		for range t.C {
			_ = st.PurgeExpiredSessions(context.Background())
		}
	}()
	go func() {
		<-ctx.Done()
		c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = hs.Shutdown(c)
	}()
	models := cat.List(ctx)
	log.Info("gbot is up", "url", "http://"+addr, "models", len(models), "ollama", cat.OllamaHost)
	if err := hs.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
