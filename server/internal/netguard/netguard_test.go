package netguard

import "testing"

func TestEndpoint(t *testing.T) {
	ok := []string{
		"https://auth.example.com/token",
		"http://localhost:8080/token",
		"http://127.0.0.1/token",
	}
	bad := []string{
		"http://auth.example.com/token",  // plain http to a remote host
		"ftp://example.com/x",            // wrong scheme
		"https://169.254.169.254/latest", // cloud metadata
		"https://[fe80::1]/x",            // link-local v6
		"https://0.0.0.0/x",              // unspecified
		"not a url",                      // unparseable
	}
	for _, u := range ok {
		if err := Endpoint(u); err != nil {
			t.Errorf("Endpoint(%q) rejected: %v", u, err)
		}
	}
	for _, u := range bad {
		if err := Endpoint(u); err == nil {
			t.Errorf("Endpoint(%q) accepted; want rejection", u)
		}
	}
}
