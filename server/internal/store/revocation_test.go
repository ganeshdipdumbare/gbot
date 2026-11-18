package store

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// Runs against a throwaway database on the local Postgres, the same way the
// api integration tests do. Set GBOT_TEST_ADMIN_URL to point elsewhere.
func revocationTestDB(t *testing.T) string {
	admin := os.Getenv("GBOT_TEST_ADMIN_URL")
	if admin == "" {
		admin = "postgres://gbot:gbot-local@127.0.0.1:55432/gbot?sslmode=disable"
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, admin)
	if err != nil {
		t.Skipf("no Postgres for integration tests: %v", err)
	}
	defer conn.Close(ctx)
	_, _ = conn.Exec(ctx, `DROP DATABASE IF EXISTS gbot_store_test WITH (FORCE)`)
	if _, err := conn.Exec(ctx, `CREATE DATABASE gbot_store_test`); err != nil {
		t.Fatal(err)
	}
	return strings.Replace(admin, "/gbot?", "/gbot_store_test?", 1)
}

// A person who created a team bot must lose access to it when they leave
// the team. Their bot membership is removed, but the owner clause used to
// keep their owned bots visible, so a removed owner's routines and queued
// work still resolved the bot.
func TestRemovedTeamMemberLosesAccessToOwnedTeamBot(t *testing.T) {
	ctx := context.Background()
	st, err := Open(ctx, revocationTestDB(t))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatal(err)
	}

	owner, err := st.CreateUser(ctx, "owner@test", "Owner", "x", "", NewBot{Name: "main", Main: true}, false)
	if err != nil {
		t.Fatal(err)
	}
	member, err := st.CreateUser(ctx, "member@test", "Member", "x", "", NewBot{Name: "main", Main: true}, false)
	if err != nil {
		t.Fatal(err)
	}
	sp, err := st.CreateSpace(ctx, owner.ID, "team")
	if err != nil {
		t.Fatal(err)
	}
	if err := st.AddSpaceMember(ctx, sp.ID, member.ID); err != nil {
		t.Fatal(err)
	}
	bot, err := st.CreateBot(ctx, sp.ID, member.ID, NewBot{Name: "helper"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.DB.Exec(ctx, `UPDATE bots SET visibility='team' WHERE id=$1`, bot.ID); err != nil {
		t.Fatal(err)
	}

	if _, err := st.GetBotFor(ctx, sp.ID, member.ID, bot.ID); err != nil {
		t.Fatalf("member lost access while still on the team: %v", err)
	}
	if err := st.RemoveSpaceMember(ctx, sp.ID, member.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := st.GetBotFor(ctx, sp.ID, member.ID, bot.ID); err == nil {
		t.Fatal("removed member still resolves their own team bot")
	}
}
