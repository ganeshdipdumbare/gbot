package api_test

import (
	"context"
	"testing"

	"gbot/internal/store"
)

func TestMemoryStore(t *testing.T) {
	dsn := testDB(t)
	ctx := context.Background()
	st, err := store.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatal(err)
	}

	m := store.NewMemory(st)
	a := store.WithScope(ctx, "bot:a")
	b := store.WithScope(ctx, "bot:b")

	if err := m.Put(a, "drink", "tea"); err != nil {
		t.Fatal(err)
	}
	if v, ok := m.Get(a, "drink"); !ok || v != "tea" {
		t.Fatalf("get: %q %v", v, ok)
	}
	if _, ok := m.Get(b, "drink"); ok {
		t.Fatal("memory leaked across bots")
	}
	if err := m.Put(a, "drink", "coffee"); err != nil {
		t.Fatal(err)
	}
	if v, _ := m.Get(a, "drink"); v != "coffee" {
		t.Fatalf("upsert: %q", v)
	}
	items, err := m.Search(a, "coff", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].Key != "drink" || items[0].Value != "coffee" {
		t.Fatalf("search: %+v", items)
	}
	if items, _ := m.Search(a, "%", 5); len(items) != 0 {
		t.Fatalf("wildcards should be literal: %+v", items)
	}
	if err := m.Delete(a, "drink"); err != nil {
		t.Fatal(err)
	}
	if _, ok := m.Get(a, "drink"); ok {
		t.Fatal("delete kept the note")
	}
}
