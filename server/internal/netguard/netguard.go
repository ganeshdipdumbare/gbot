// Package netguard makes outbound requests that the server sends on
// behalf of a person, to addresses that person chose or a remote server
// described. Those addresses are untrusted, so this stops the requests from
// reaching cloud metadata services and from following redirects.
package netguard

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"time"
)

// BlockPrivate, set for hosted installs, also refuses loopback and private
// network addresses, so a user-supplied address cannot reach the server's
// own network. Self-hosted installs leave it off, since local model and
// tool servers are the point of running gbot at home.
var BlockPrivate bool

// Dial refuses link-local addresses, which include cloud metadata services
// (169.254.169.254, fd00:ec2::254). Loopback and LAN addresses stay
// reachable, because local and home-network model and tool servers are a
// supported setup.
func Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil || len(ips) == 0 {
		return nil, fmt.Errorf("couldn't resolve %s", host)
	}
	for _, ip := range ips {
		if ip.IP.IsLinkLocalUnicast() || ip.IP.IsLinkLocalMulticast() || ip.IP.IsUnspecified() {
			return nil, fmt.Errorf("%s is not a server address", host)
		}
		if BlockPrivate && (ip.IP.IsLoopback() || ip.IP.IsPrivate()) {
			return nil, fmt.Errorf("%s is on this server's network, which this install does not reach", host)
		}
	}
	var d net.Dialer
	d.Timeout = 10 * time.Second
	// Dial the address that was checked, so a second lookup cannot differ.
	return d.DialContext(ctx, network, net.JoinHostPort(ips[0].IP.String(), port))
}

// Client returns an HTTP client with the dial guard, no proxy from the
// environment (a proxy would skip the guard), and no redirects.
func Client(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout:       timeout,
		Transport:     &http.Transport{DialContext: Dial, Proxy: nil},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// Endpoint checks a URL a remote server handed us. Only https is accepted,
// or plain http to this machine, and never a link-local address.
func Endpoint(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return errors.New("bad endpoint URL")
	}
	host := u.Hostname()
	local := host == "localhost" || host == "127.0.0.1" || host == "::1"
	switch {
	case u.Scheme == "https":
	case u.Scheme == "http" && local:
	default:
		return errors.New("sign-in endpoints must use https")
	}
	if ip := net.ParseIP(host); ip != nil && (ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsUnspecified()) {
		return errors.New("sign-in endpoints can't point at this network's link-local addresses")
	}
	return nil
}
