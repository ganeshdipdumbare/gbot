package api

import "testing"

// A server listening on 127.0.0.1 answers only to names it knows, so a
// page that rebinds its own name to 127.0.0.1 can't use it.
func TestLoopbackServerRefusesUnknownNames(t *testing.T) {
	s := &Server{LoopbackOnly: true, PublicURL: "https://bots.example.com", ExtraHosts: []string{"gbot.lan"}}
	for host, want := range map[string]bool{
		"127.0.0.1:8080": true, "[::1]:8080": true, "localhost:8080": true, "app.localhost": true,
		"bots.example.com": true, "gbot.lan:8080": true, "192.168.1.5:8080": true,
		"evil.example:8080": false, "rebind.attacker.test": false,
	} {
		if got := s.hostAllowed(host); got != want {
			t.Errorf("%s: got %v, want %v", host, got, want)
		}
	}
	if !(&Server{}).hostAllowed("anything.example") {
		t.Error("a server not bound to loopback should not check names")
	}
	if !(&Server{LoopbackOnly: true, TrustProxy: true}).hostAllowed("bots.example.com") {
		t.Error("behind a trusted proxy the proxy picks the name")
	}
}
