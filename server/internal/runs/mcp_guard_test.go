package runs

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

// An MCP server must not be able to bounce the server's request to another
// address. The probe uses the same guarded client as provider calls, which
// refuses redirects, so the marker endpoint is never reached.
func TestMCPProbeDoesNotFollowRedirects(t *testing.T) {
	var hits atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer target.Close()

	redirector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL+"/internal", http.StatusTemporaryRedirect)
	}))
	defer redirector.Close()

	r := &Runtime{}
	_, _ = r.probe(mcpServer{id: "t", name: "t", url: redirector.URL})
	if n := hits.Load(); n != 0 {
		t.Fatalf("MCP probe followed a redirect to an internal address (%d hits)", n)
	}
}
