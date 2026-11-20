package runs

// Fast web lookups. Research used to go through the browser tool, which
// opens a page, snapshots it and clicks around: many slow steps for a
// question a search answers in one. web_search returns titles, links and
// snippets in about a second; web_fetch reads one page as text, with its
// picture and title up front so the bot can hand them to show_results.
//
// Both go out through the guarded client (no private or loopback hosts),
// and what they return is outside text: the run is tainted after them.

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"gbot/internal/netguard"
)

const (
	maxSearchHits  = 8
	maxFetchBytes  = 1 << 20 // read at most 1 MB of a page
	maxFetchText   = 12000   // and hand the model at most this much text
	webTimeout     = 12 * time.Second
	webUserAgent   = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
	searchEndpoint = "https://www.bing.com/search"
)

// linksIn returns the https links a person wrote. Trailing punctuation goes,
// but a closing ) stays when the link has its own opening one, as in
// .../Mercury_(planet).
func linksIn(text string) []string {
	var out []string
	for _, u := range linkRE.FindAllString(text, 20) {
		for u != "" {
			last := u[len(u)-1]
			if strings.IndexByte(".,;:!?\"'", last) >= 0 || (last == ')' && strings.Count(u, ")") > strings.Count(u, "(")) {
				u = u[:len(u)-1]
				continue
			}
			break
		}
		out = append(out, u)
	}
	return out
}

var linkRE = regexp.MustCompile(`https://[^\s<>"']+`)

type searchIn struct {
	Query string `json:"query" desc:"what to search for, in plain words"`
}

type fetchIn struct {
	URL string `json:"url" desc:"an https page from a search result, exactly as given"`
}

var (
	algoRE    = regexp.MustCompile(`(?is)<li class="b_algo"[^>]*>(.*?)</li>`)
	headRE    = regexp.MustCompile(`(?is)<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>`)
	snippetRE = regexp.MustCompile(`(?is)<p[^>]*class="[^"]*b_(?:lineclamp|algoSlug)[^"]*"[^>]*>(.*?)</p>|<div class="b_caption[^"]*"[^>]*>.*?<p[^>]*>(.*?)</p>`)
	tagRE     = regexp.MustCompile(`(?s)<[^>]*>`)
	spaceRE   = regexp.MustCompile(`[ \t\r\f\v]+`)
	blankRE   = regexp.MustCompile(`\n\s*\n+`)
	dropRE    = regexp.MustCompile(`(?is)<(script|style|noscript|svg|template|head)\b[^>]*>.*?</(script|style|noscript|svg|template|head)\s*>|<!--.*?-->`)
	breakRE   = regexp.MustCompile(`(?i)</?(br|p|div|li|h[1-6]|tr|table|section|article)\b[^>]*>`)
	titleRE   = regexp.MustCompile(`(?is)<title[^>]*>(.*?)</title>`)
	ogImageRE = regexp.MustCompile(`(?is)<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]*>`)
	contentRE = regexp.MustCompile(`(?is)content=["']([^"']+)["']`)
)

type searchHit struct{ Title, URL, Snippet string }

// parseSearch reads the hits out of a Bing results page.
func parseSearch(page string) []searchHit {
	var hits []searchHit
	for _, m := range algoRE.FindAllStringSubmatch(page, -1) {
		if len(hits) == maxSearchHits {
			break
		}
		h := headRE.FindStringSubmatch(m[1])
		if h == nil {
			continue
		}
		u := searchTarget(html.UnescapeString(h[1]))
		if u == "" {
			continue
		}
		hit := searchHit{Title: plain(h[2]), URL: u}
		if sn := snippetRE.FindStringSubmatch(m[1]); sn != nil {
			hit.Snippet = plain(sn[1] + sn[2])
		}
		hits = append(hits, hit)
	}
	return hits
}

// searchTarget unwraps Bing's tracking link (the page address is base64 in
// its u parameter, after an "a1" marker) and keeps only plain https links.
func searchTarget(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	if u.Host == "www.bing.com" && strings.HasPrefix(u.Path, "/ck/") {
		enc := strings.TrimPrefix(u.Query().Get("u"), "a1")
		dec, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(enc, "="))
		if err != nil {
			return ""
		}
		return safeLink(string(dec))
	}
	if strings.HasSuffix(u.Host, "bing.com") {
		return ""
	}
	return safeLink(u.String())
}

func plain(s string) string {
	s = html.UnescapeString(tagRE.ReplaceAllString(s, ""))
	return strings.TrimSpace(spaceRE.ReplaceAllString(s, " "))
}

// get reads one page through the guarded client.
func get(ctx context.Context, rawURL string, post url.Values) ([]byte, string, error) {
	if err := netguard.Endpoint(rawURL); err != nil {
		return nil, "", err
	}
	ctx, cancel := context.WithTimeout(ctx, webTimeout)
	defer cancel()
	method, payload := http.MethodGet, io.Reader(nil)
	if post != nil {
		method, payload = http.MethodPost, strings.NewReader(post.Encode())
	}
	req, err := http.NewRequestWithContext(ctx, method, rawURL, payload)
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("User-Agent", webUserAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
	req.Header.Set("Accept-Language", "en")
	if post != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	// One lookup per connection: nothing reuses it, so don't leave it idle.
	req.Close = true
	client := netguard.Client(webTimeout)
	// Redirect hops are new requests, so closing the first isn't enough:
	// keep nothing alive on this throwaway transport.
	if t, ok := client.Transport.(*http.Transport); ok {
		t.DisableKeepAlives = true
	}
	// Follow a few redirects (http to https, www, locale), each still https
	// and each dialled through the guard.
	client.CheckRedirect = func(next *http.Request, via []*http.Request) error {
		if len(via) >= 4 {
			return errors.New("too many redirects")
		}
		if next.URL.Scheme != "https" {
			return errors.New("redirected away from https")
		}
		// A bot's site allowlist holds for every hop, not just the first.
		if !hostAllowed(allowedHostsFrom(ctx), next.URL.String()) {
			return errors.New("redirected to a site that isn't on this bot's allowlist")
		}
		return netguard.Endpoint(next.URL.String())
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, "", fmt.Errorf("the site answered %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxFetchBytes))
	return raw, resp.Header.Get("Content-Type"), err
}

// webSearch runs one search and formats the hits for the model. The links
// it returns are the ones web_fetch may read later in the same run.
func (r *Runtime) webSearch(ctx context.Context, in searchIn) (string, error) {
	q := strings.TrimSpace(in.Query)
	if q == "" {
		return "", errors.New("give a query")
	}
	body, _, err := get(ctx, searchEndpoint+"?setlang=en&q="+url.QueryEscape(q), nil)
	if err != nil {
		return "", fmt.Errorf("search failed: %w", err)
	}
	hits := parseSearch(string(body))
	if len(hits) == 0 {
		return "No results. Try different words.", nil
	}
	var sb strings.Builder
	rc := runFrom(ctx)
	for i, h := range hits {
		rc.allowFetch(h.URL)
		fmt.Fprintf(&sb, "%d. %s\n   %s\n", i+1, h.Title, h.URL)
		if h.Snippet != "" {
			fmt.Fprintf(&sb, "   %s\n", h.Snippet)
		}
	}
	return sb.String(), nil
}

type hostsKey struct{}

// withAllowedHosts carries a bot's site allowlist to the fetch, so redirect
// hops are held to it too.
func withAllowedHosts(ctx context.Context, hosts []string) context.Context {
	return context.WithValue(ctx, hostsKey{}, hosts)
}

func allowedHostsFrom(ctx context.Context) []string {
	h, _ := ctx.Value(hostsKey{}).([]string)
	return h
}

// webFetch reads one page as text.
func (r *Runtime) webFetch(ctx context.Context, in fetchIn) (string, error) {
	u := safeLink(in.URL)
	if u == "" {
		return "", errors.New("give an absolute https URL")
	}
	// Only a page the search returned, or one the person wrote in their
	// message. A page the bot makes up could carry what it knows to a site
	// an injected instruction chose, and this tool asks no approval.
	if !runFrom(ctx).mayFetch(u) {
		return "", errors.New("that page didn't come from a search result or the person's message. Search for it first, or use browser_navigate, which asks the person")
	}
	body, ctype, err := get(ctx, u, nil)
	if err != nil {
		return "", fmt.Errorf("couldn't read the page: %w", err)
	}
	return pageText(u, string(body), ctype), nil
}

// pageText turns a page into a title, its picture and readable text.
func pageText(base, page, ctype string) string {
	if strings.Contains(strings.ToLower(ctype), "html") || strings.Contains(strings.ToLower(page[:min(len(page), 512)]), "<html") {
		var head strings.Builder
		if m := titleRE.FindStringSubmatch(page); m != nil {
			fmt.Fprintf(&head, "Title: %s\n", plain(m[1]))
		}
		if m := ogImageRE.FindString(page); m != "" {
			if c := contentRE.FindStringSubmatch(m); c != nil {
				if img := absURL(base, html.UnescapeString(c[1])); img != "" {
					fmt.Fprintf(&head, "Image: %s\n", img)
				}
			}
		}
		page = dropRE.ReplaceAllString(page, "")
		page = breakRE.ReplaceAllString(page, "\n")
		page = html.UnescapeString(tagRE.ReplaceAllString(page, ""))
		page = spaceRE.ReplaceAllString(page, " ")
		page = strings.ReplaceAll(page, "\n ", "\n")
		page = blankRE.ReplaceAllString(page, "\n\n")
		return head.String() + "\n" + clip(strings.TrimSpace(page), maxFetchText)
	}
	return clip(strings.TrimSpace(page), maxFetchText)
}

// absURL resolves a link found on a page against the page's address and
// keeps it only if it ends up an https URL.
func absURL(base, ref string) string {
	b, err := url.Parse(base)
	if err != nil {
		return ""
	}
	u, err := b.Parse(strings.TrimSpace(ref))
	if err != nil {
		return ""
	}
	return safeLink(u.String())
}
