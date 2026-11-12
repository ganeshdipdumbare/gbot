package runs

import "testing"

func TestFetchesWeb(t *testing.T) {
	for _, cmd := range []string{
		"curl -s https://example.com",
		"wget https://example.com",
		"python3 -c 'import requests; requests.get(u)'",
	} {
		if !fetchesWeb(cmd) {
			t.Fatalf("%q should be treated as a web fetch", cmd)
		}
	}
	for _, cmd := range []string{
		"python3 fib.py",
		"git status",
		"cat notes.md",
		"echo hello",
	} {
		if fetchesWeb(cmd) {
			t.Fatalf("%q is local work", cmd)
		}
	}
}
