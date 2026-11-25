package runs

import (
	"context"
	"io"
	"log/slog"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/ganeshdipdumbare/kritrim"
	"github.com/jackc/pgx/v5"

	"gbot/internal/store"
)

func user(s string) kritrim.Message { return kritrim.Message{Role: kritrim.RoleUser, Content: s} }
func said(s string) kritrim.Message { return kritrim.Message{Role: kritrim.RoleAssistant, Content: s} }

func fetch(id string, page string) []kritrim.Message {
	return []kritrim.Message{
		{Role: kritrim.RoleAssistant, Reasoning: "let me look", ReasoningSig: "sig",
			ToolCalls: []kritrim.ToolCall{{ID: id, Name: "web_fetch", Args: map[string]any{"url": "https://example.com"}}}},
		{Role: kritrim.RoleTool, ToolCallID: id, Content: page},
	}
}

// One earlier turn that read a long page, then the current turn reading another.
func pageHistory() []kritrim.Message {
	var h []kritrim.Message
	h = append(h, user("what does example.com say?"))
	h = append(h, fetch("c1", "OPENING "+strings.Repeat("old page ", 2000))...)
	h = append(h, said("It says hello."))
	h = append(h, user("and now?"))
	h = append(h, fetch("c2", strings.Repeat("new page ", 2000))...)
	return h
}

func TestEarlierToolAnswersAreShortened(t *testing.T) {
	h := pageHistory()
	got := lightenEarlier(h)
	old := got[2]
	if len(old.Content) > staleToolHead+300 || !strings.Contains(old.Content, "web_fetch") || !strings.Contains(old.Content, "OPENING") {
		t.Fatalf("earlier page not shortened to its opening (%d chars):\n%.300s", len(old.Content), old.Content)
	}
	if got[1].Reasoning != "" || got[1].ReasoningSig != "" {
		t.Fatal("earlier turn's thinking was resent")
	}
	if got[6].Content != h[6].Content || got[5].ReasoningSig != "sig" {
		t.Fatal("the current turn must stay whole")
	}
	if h[2].Content == old.Content {
		t.Fatal("the stored history was changed in place")
	}
	if kritrim.EstimateTokens(got) > kritrim.EstimateTokens(h)*2/3 {
		t.Fatalf("prompt barely shrank: %d → %d tokens", kritrim.EstimateTokens(h), kritrim.EstimateTokens(got))
	}
}

func TestWindowStartsAtThePersonAndKeepsTheCurrentTurn(t *testing.T) {
	h := pageHistory()
	start := windowStart(h, 10)
	if start != 4 || h[start].Role != kritrim.RoleUser {
		t.Fatalf("window starts at %d, want the current turn at 4", start)
	}
	if windowStart(h, 1_000_000) != 0 {
		t.Fatal("everything fits, nothing should be dropped")
	}
}

func TestOneLongTurnKeepsItsNewestToolAnswers(t *testing.T) {
	h := []kritrim.Message{user("research it")}
	for _, id := range []string{"a", "b", "c", "d", "e", "f"} {
		h = append(h, fetch(id, strings.Repeat(id, 5000))...)
	}
	got := lightenTurn(h, 1000)
	var whole, short int
	for _, m := range got {
		if m.Role != kritrim.RoleTool {
			continue
		}
		if len(m.Content) == 5000 {
			whole++
		} else {
			short++
		}
	}
	if whole != freshToolResults || short != 6-freshToolResults {
		t.Fatalf("whole=%d short=%d", whole, short)
	}
	if got[len(got)-1].Content != h[len(h)-1].Content {
		t.Fatal("the newest answer was shortened")
	}
}

// slowModel stands in for a provider that takes a while to summarize.
type slowModel struct {
	release chan struct{}
	calls   chan struct{}
}

func (m *slowModel) Generate(ctx context.Context, req kritrim.Request) (*kritrim.Response, error) {
	m.calls <- struct{}{}
	select {
	case <-m.release:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	return &kritrim.Response{Text: "They asked about example.com; it says hello."}, nil
}

func (m *slowModel) Stream(ctx context.Context, req kritrim.Request, emit func(kritrim.Delta)) (*kritrim.Response, error) {
	return m.Generate(ctx, req)
}

func TestLongChatNeverWaitsForItsSummary(t *testing.T) {
	st := runsDB(t)
	r := &Runtime{cfg: Config{ContextTokens: 1500}, store: st, log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	var h []kritrim.Message
	for i := 0; i < 12; i++ {
		h = append(h, user("question "+strings.Repeat("words ", 120)), said("answer "+strings.Repeat("words ", 120)))
	}
	h = append(h, user("latest question"))
	seed(t, st, "t-window", h)
	m := &slowModel{release: make(chan struct{}), calls: make(chan struct{}, 4)}
	ctx := withThread(context.Background(), "t-window")

	done := make(chan *kritrim.Request)
	go func() {
		req := &kritrim.Request{Messages: h}
		r.fitContext(ctx, m, req)
		done <- req
	}()
	var req *kritrim.Request
	select {
	case req = <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("the step waited for the summary")
	}
	select {
	case <-m.calls:
	case <-time.After(5 * time.Second):
		t.Fatal("no summary was started in the background")
	}
	if last := req.Messages[len(req.Messages)-1]; last.Content != "latest question" {
		t.Fatalf("lost the person's message: %+v", last)
	}

	close(m.release)
	deadline := time.Now().Add(5 * time.Second)
	for {
		if s, _ := st.GetSummary(context.Background(), "t-window"); s != nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the background summary was never saved")
		}
		time.Sleep(20 * time.Millisecond)
	}
	for r.summarizingNow("t-window") {
		time.Sleep(10 * time.Millisecond)
	}

	req = &kritrim.Request{Messages: h}
	r.fitContext(ctx, m, req)
	if !strings.Contains(req.Messages[0].Content, "it says hello") {
		t.Fatalf("the saved summary didn't stand in for the old turns: %.200s", req.Messages[0].Content)
	}
	if n := kritrim.EstimateTokens(req.Messages); n > 1500 {
		t.Fatalf("prompt still over budget: %d tokens", n)
	}
	select {
	case <-m.calls:
		t.Fatal("summarized again although the summary is current")
	default:
	}
}

func (r *Runtime) summarizingNow(th string) bool {
	_, busy := r.summarizing.Load(th)
	return busy
}

// runsDB is a fresh database of its own, so this package's tests never
// share one with another package's running at the same time.
func runsDB(t *testing.T) *store.Store {
	t.Helper()
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
	_, _ = conn.Exec(ctx, `DROP DATABASE IF EXISTS gbot_test_runs WITH (FORCE)`)
	if _, err := conn.Exec(ctx, `CREATE DATABASE gbot_test_runs`); err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(ctx, strings.Replace(admin, "/gbot?", "/gbot_test_runs?", 1))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(st.Close)
	if err := st.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	return st
}

func longChat(turns int) []kritrim.Message {
	var h []kritrim.Message
	for i := 0; i < turns; i++ {
		h = append(h, user("question "+strings.Repeat("words ", 120)), said("answer "+strings.Repeat("words ", 120)))
	}
	return append(h, user("latest question"))
}

func quietRuntime(st *store.Store, budget int) *Runtime {
	return &Runtime{cfg: Config{ContextTokens: budget}, store: st, log: slog.New(slog.NewTextHandler(io.Discard, nil))}
}

// A saved summary can cover more than the current window drops, e.g. once
// a heavy turn's pages are shortened and the window reaches further back.
func TestSummaryCoveringPastTheWindowIsSafe(t *testing.T) {
	st := runsDB(t)
	r := quietRuntime(st, 1500)
	h := longChat(12)
	seed(t, st, "t-past", h)
	if err := st.SaveSummary(context.Background(), "t-past", len(h)-1, "they like tea"); err != nil {
		t.Fatal(err)
	}
	req := &kritrim.Request{Messages: h}
	r.fitContext(withThread(context.Background(), "t-past"), &slowModel{calls: make(chan struct{}, 4)}, req)
	if !strings.Contains(req.Messages[0].Content, "they like tea") {
		t.Fatalf("saved summary not used: %.200s", req.Messages[0].Content)
	}
	if last := req.Messages[len(req.Messages)-1]; last.Content != "latest question" {
		t.Fatalf("lost the person's message: %+v", last)
	}
}

// The rough fallback joined to a clean saved summary still says it holds
// outside text, so the run is tainted.
func TestRoughFallbackKeepsTheOutsideMark(t *testing.T) {
	st := runsDB(t)
	r := quietRuntime(st, 1500)
	h := append([]kritrim.Message{user("read example.com")}, fetch("w1", "page text")...)
	h = append(h, longChat(30)...)
	seed(t, st, "t-mark", h)
	if err := st.SaveSummary(context.Background(), "t-mark", 0, "they like tea"); err != nil {
		t.Fatal(err)
	}
	m := &slowModel{release: make(chan struct{}), calls: make(chan struct{}, 4)}
	defer close(m.release)
	req := &kritrim.Request{Messages: h}
	r.fitContext(withThread(context.Background(), "t-mark"), m, req)
	if !strings.Contains(req.Messages[0].Content, "they like tea") || !strings.Contains(req.Messages[0].Content, outsideMark) {
		t.Fatalf("combined summary lost the saved text or the outside mark:\n%.300s", req.Messages[0].Content)
	}
}

type failingModel struct{ calls chan struct{} }

func (m *failingModel) Generate(ctx context.Context, req kritrim.Request) (*kritrim.Response, error) {
	m.calls <- struct{}{}
	return nil, context.DeadlineExceeded
}

func (m *failingModel) Stream(ctx context.Context, req kritrim.Request, emit func(kritrim.Delta)) (*kritrim.Response, error) {
	return m.Generate(ctx, req)
}

func TestFailingSummaryIsNotRetriedEveryStep(t *testing.T) {
	st := runsDB(t)
	r := quietRuntime(st, 1500)
	m := &failingModel{calls: make(chan struct{}, 8)}
	ctx := withThread(context.Background(), "t-fail")
	r.fitContext(ctx, m, &kritrim.Request{Messages: longChat(12)})
	<-m.calls
	for r.summarizingNow("t-fail") {
		time.Sleep(10 * time.Millisecond)
	}
	for i := 0; i < 3; i++ {
		r.fitContext(ctx, m, &kritrim.Request{Messages: longChat(12)})
	}
	time.Sleep(100 * time.Millisecond)
	if n := len(m.calls); n != 0 {
		t.Fatalf("a failing summary was retried %d more times right away", n)
	}
}

func seed(t *testing.T, st *store.Store, th string, h []kritrim.Message) {
	t.Helper()
	if err := store.NewSessions(st).Save(context.Background(), &kritrim.Session{ID: th, Messages: h}); err != nil {
		t.Fatal(err)
	}
}

// A summary that finishes after the chat was cleared must not bring the
// cleared conversation back, and an older summary never replaces a newer one.
func TestLateSummaryIsDropped(t *testing.T) {
	st := runsDB(t)
	ctx := context.Background()
	h := longChat(12)
	seed(t, st, "t-late", h)
	if err := st.SaveSummary(ctx, "t-late", 20, "newer"); err != nil {
		t.Fatal(err)
	}
	if err := st.SaveSummary(ctx, "t-late", 10, "older"); err != nil {
		t.Fatal(err)
	}
	if s, _ := st.GetSummary(ctx, "t-late"); s == nil || s.Summary != "newer" {
		t.Fatalf("older summary replaced the newer one: %+v", s)
	}

	r := quietRuntime(st, 1500)
	m := &slowModel{release: make(chan struct{}), calls: make(chan struct{}, 4)}
	r.fitContext(withThread(ctx, "t-late"), m, &kritrim.Request{Messages: append(h, longChat(12)...)})
	<-m.calls
	if _, err := st.DB.Exec(ctx, `DELETE FROM agent_sessions WHERE id=$1`, "t-late"); err != nil {
		t.Fatal(err)
	}
	if _, err := st.DB.Exec(ctx, `DELETE FROM summaries WHERE thread_id=$1`, "t-late"); err != nil {
		t.Fatal(err)
	}
	close(m.release)
	for r.summarizingNow("t-late") {
		time.Sleep(10 * time.Millisecond)
	}
	if s, _ := st.GetSummary(ctx, "t-late"); s != nil {
		t.Fatalf("a cleared chat's summary came back: %+v", s)
	}
}

// Clearing a chat stops its summary, even if the person starts a long new
// chat on the same thread before the old summary would have finished.
func TestClearingAChatStopsItsSummary(t *testing.T) {
	st := runsDB(t)
	ctx := context.Background()
	r := quietRuntime(st, 1500)
	h := longChat(12)
	seed(t, st, "t-clear", h)
	m := &slowModel{release: make(chan struct{}), calls: make(chan struct{}, 4)}
	defer close(m.release)
	r.fitContext(withThread(ctx, "t-clear"), m, &kritrim.Request{Messages: h})
	<-m.calls

	r.stopSummary("t-clear")
	if r.summarizingNow("t-clear") {
		t.Fatal("summary still running after the chat was cleared")
	}
	if _, err := st.ClearChat(ctx, "00000000-0000-0000-0000-000000000000", "00000000-0000-0000-0000-000000000000", "t-clear", false); err != nil {
		t.Fatal(err)
	}
	seed(t, st, "t-clear", longChat(30)) // a new, longer chat on the same thread
	time.Sleep(50 * time.Millisecond)
	if s, _ := st.GetSummary(ctx, "t-clear"); s != nil {
		t.Fatalf("the cleared chat's summary was saved: %+v", s)
	}
}

// A run that began before the clear and summarizes after it (it was
// stopped, but reached its next step) doesn't save the old chat.
func TestRunFromBeforeAClearDoesNotSaveItsSummary(t *testing.T) {
	st := runsDB(t)
	ctx := context.Background()
	r := quietRuntime(st, 1500)
	h := longChat(12)
	seed(t, st, "t-stale", h)
	runCtx := r.withThread(ctx, "t-stale")
	r.stopSummary("t-stale") // the person clears the chat
	if _, err := st.ClearChat(ctx, "00000000-0000-0000-0000-000000000000", "00000000-0000-0000-0000-000000000000", "t-stale", false); err != nil {
		t.Fatal(err)
	}
	seed(t, st, "t-stale", longChat(30))

	m := &slowModel{release: make(chan struct{}), calls: make(chan struct{}, 4)}
	close(m.release)
	r.fitContext(runCtx, m, &kritrim.Request{Messages: h})
	<-m.calls
	for r.summarizingNow("t-stale") {
		time.Sleep(10 * time.Millisecond)
	}
	if s, _ := st.GetSummary(ctx, "t-stale"); s != nil {
		t.Fatalf("a run from before the clear saved the old chat's summary: %+v", s)
	}
	// A run that begins after the clear does save.
	r.fitContext(r.withThread(ctx, "t-stale"), m, &kritrim.Request{Messages: longChat(30)})
	<-m.calls
	for r.summarizingNow("t-stale") {
		time.Sleep(10 * time.Millisecond)
	}
	if s, _ := st.GetSummary(ctx, "t-stale"); s == nil {
		t.Fatal("a run after the clear couldn't save its summary")
	}
}
