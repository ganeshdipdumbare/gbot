package api

import "testing"

func TestCleanHosts(t *testing.T) {
	got, msg := cleanHosts([]string{"https://LinkedIn.com/jobs?q=go", "*.indeed.com", "example.com/path", "Example.COM", "https://www.google.com/search?q=go"})
	if msg != "" {
		t.Fatalf("cleanHosts: %s", msg)
	}
	want := []string{"linkedin.com", "indeed.com", "example.com", "google.com"}
	if len(got) != len(want) {
		t.Fatalf("hosts = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("hosts = %v, want %v", got, want)
		}
	}
	if _, msg := cleanHosts([]string{"not a host"}); msg == "" {
		t.Fatal("a bad host was accepted")
	}
}
