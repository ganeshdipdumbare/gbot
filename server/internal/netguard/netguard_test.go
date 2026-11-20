package netguard

import (
	"context"
	"net"
	"testing"
)

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

func TestDialBlocksPrivateWhenHosted(t *testing.T) {
	BlockPrivate = true
	defer func() { BlockPrivate = false }()
	if _, err := Dial(context.Background(), "tcp", "127.0.0.1:9"); err == nil {
		t.Fatal("loopback dial allowed while BlockPrivate is set")
	}
}

func TestRefusedMetadataInEveryMode(t *testing.T) {
	old := BlockPrivate
	defer func() { BlockPrivate = old }()
	BlockPrivate = false
	for _, a := range []string{"169.254.169.254", "fd00:ec2::254", "100.100.100.200", "168.63.129.16", "192.0.0.192", "64:ff9b::a9fe:a9fe", "0.0.0.0"} {
		if Refused(net.ParseIP(a)) == "" {
			t.Errorf("%s allowed with BlockPrivate off", a)
		}
	}
	for _, a := range []string{"127.0.0.1", "192.168.1.10", "100.64.0.1", "93.184.216.34"} {
		if why := Refused(net.ParseIP(a)); why != "" {
			t.Errorf("%s refused with BlockPrivate off: %s", a, why)
		}
	}
	BlockPrivate = true
	for _, a := range []string{"100.64.0.1", "198.18.0.1", "127.0.0.1", "10.0.0.1"} {
		if Refused(net.ParseIP(a)) == "" {
			t.Errorf("%s allowed on a hosted install", a)
		}
	}
}
