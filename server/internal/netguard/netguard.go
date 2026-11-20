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
	"net/netip"
	"net/url"
	"time"
)

// BlockPrivate, set for hosted installs, also refuses loopback and private
// network addresses, so a user-supplied address cannot reach the server's
// own network. Self-hosted installs leave it off, since local model and
// tool servers are the point of running gbot at home.
var BlockPrivate bool

// metadata are cloud metadata and host-agent addresses outside the
// link-local range; they are refused in every mode.
var metadata = []netip.Prefix{
	netip.MustParsePrefix("fd00:ec2::254/128"),  // AWS IPv6 metadata (unique-local, not link-local)
	netip.MustParsePrefix("100.100.100.200/32"), // Alibaba Cloud metadata
	netip.MustParsePrefix("168.63.129.16/32"),   // Azure wire server
	netip.MustParsePrefix("192.0.0.192/32"),     // Oracle Cloud metadata
	netip.MustParsePrefix("0.0.0.0/8"),          // "this network"
}

// internal are non-RFC 1918 ranges that still reach a provider's or the
// host's own network (carrier NAT and Tailscale, benchmarking, IETF
// special use). Refused with BlockPrivate, like loopback and private.
var internal = []netip.Prefix{
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"),
}

// nat64 maps IPv4 addresses into IPv6 (64:ff9b::/96); the IPv4 address
// inside is what is reached, so that is what gets checked.
var nat64 = netip.MustParsePrefix("64:ff9b::/96")

// Refused says why an address may not be dialed, or "" when it may.
func Refused(ip net.IP) string {
	a, ok := netip.AddrFromSlice(ip)
	if !ok {
		return "not a server address"
	}
	a = a.Unmap()
	if nat64.Contains(a) {
		b := a.As16()
		a = netip.AddrFrom4([4]byte{b[12], b[13], b[14], b[15]})
	}
	if a.IsLinkLocalUnicast() || a.IsLinkLocalMulticast() || a.IsUnspecified() {
		return "not a server address"
	}
	for _, p := range metadata {
		if p.Contains(a) {
			return "not a server address"
		}
	}
	if BlockPrivate {
		if a.IsLoopback() || a.IsPrivate() {
			return "on this server's network, which this install does not reach"
		}
		for _, p := range internal {
			if p.Contains(a) {
				return "on this server's network, which this install does not reach"
			}
		}
	}
	return ""
}

// Dial refuses link-local addresses and the other cloud metadata addresses
// in metadata, in every mode. Loopback and LAN addresses stay reachable
// unless BlockPrivate is set, because local and home-network model and
// tool servers are a supported setup.
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
		if why := Refused(ip.IP); why != "" {
			return nil, fmt.Errorf("%s is %s", host, why)
		}
	}
	var d net.Dialer
	d.Timeout = 10 * time.Second
	// Dial the address that was checked, so a second lookup cannot differ.
	return d.DialContext(ctx, network, net.JoinHostPort(ips[0].IP.String(), port))
}

// Client returns an HTTP client with the dial guard, no proxy from the
// environment (a proxy would skip the guard), and no redirects. Idle
// connections close after a while: a client is often built per call, and
// a server that never hangs up would otherwise keep each one open forever.
func Client(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{DialContext: Dial, Proxy: nil,
			IdleConnTimeout: 90 * time.Second, MaxIdleConnsPerHost: 4},
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
	if ip := net.ParseIP(host); ip != nil && Refused(ip) == "not a server address" {
		return errors.New("sign-in endpoints can't point at this network's link-local addresses")
	}
	return nil
}
