package runs

import (
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
