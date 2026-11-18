package runs

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestResultSetNeedsTwoTitledItems(t *testing.T) {
	one := resultsIn{Items: []resultItem{{Title: "A"}}}
	if _, ok := resultSetFrom(one); ok {
		t.Fatal("one item is a sentence, not a list")
	}
	two := resultsIn{Title: "  Hotels  ", Items: []resultItem{{Title: "A"}, {Subtitle: "no title"}, {Title: "B"}}}
	set, ok := resultSetFrom(two)
	if !ok || len(set.Items) != 2 || set.Title != "Hotels" {
		t.Fatalf("two titled items: ok=%v set=%+v", ok, set)
	}
}

func TestResultSetCapsItems(t *testing.T) {
	in := resultsIn{}
	for i := 0; i < 20; i++ {
		in.Items = append(in.Items, resultItem{Title: "x"})
	}
	set, _ := resultSetFrom(in)
	if len(set.Items) != maxResultCards {
		t.Fatalf("items should be capped at %d, got %d", maxResultCards, len(set.Items))
	}
}

func TestCardFromItemDropsUnsafeLinksAndImages(t *testing.T) {
	card, ok := cardFromItem(resultItem{
		Title: "Echo Hotel and Spa",
		Link:  "javascript:alert(1)",
		Image: "http://example.com/a.jpg",
	})
	if !ok || card.Title != "Echo Hotel and Spa" {
		t.Fatalf("card: %+v ok=%v", card, ok)
	}
	if card.Link != "" {
		t.Fatalf("javascript: link must be dropped, got %q", card.Link)
	}
	if card.imageSrc != "" {
		t.Fatalf("http image must not be fetched, got %q", card.imageSrc)
	}
}

func TestCardFromItemHttpsLinkAndImage(t *testing.T) {
	card, _ := cardFromItem(resultItem{Title: "X", Link: "https://example.com/x", Image: "https://example.com/x.jpg"})
	if card.Link != "https://example.com/x" || card.imageSrc != "https://example.com/x.jpg" {
		t.Fatalf("https link/image: %+v", card)
	}
}

func TestCardFieldLimitAndEmptyLines(t *testing.T) {
	it := resultItem{Title: "X", Fields: []resultLine{{Label: "", Value: "lost"}, {Label: "Empty", Value: "  "}}}
	for _, l := range []string{"a", "b", "c", "d", "e", "f", "g", "h"} {
		it.Fields = append(it.Fields, resultLine{Label: l, Value: "v"})
	}
	card, _ := cardFromItem(it)
	if len(card.Fields) != maxResultFields {
		t.Fatalf("fields should be capped at %d, got %d", maxResultFields, len(card.Fields))
	}
	if card.Fields[0].Label != "a" {
		t.Fatalf("empty label or value must be skipped, got %+v", card.Fields)
	}
}

func TestClipKeepsRunesWhole(t *testing.T) {
	out := clipText(strings.Repeat("é", 300))
	if len([]rune(out)) != maxResultText {
		t.Fatalf("clip length: %d runes", len([]rune(out)))
	}
}

func TestSafeLink(t *testing.T) {
	cases := map[string]bool{
		"https://example.com/a":    true,
		"  https://example.com/a ": true,
		"http://example.com/a":     false,
		"javascript:alert(1)":      false,
		"data:text/html,x":         false,
		"":                         false,
	}
	for in, want := range cases {
		if (safeLink(in) != "") != want {
			t.Errorf("safeLink(%q): want ok=%v", in, want)
		}
	}
}

// The card JSON is what the chat reads, so its field names are part of the
// contract the web and Android clients depend on.
func TestCardJSONShape(t *testing.T) {
	raw, _ := json.Marshal(resultCard{Title: "T", Image: "blob1", Mime: "image/jpeg", Link: "https://e.com", Fields: []resultField{{Label: "Rating", Value: "9"}}})
	s := string(raw)
	for _, want := range []string{`"title":"T"`, `"image":"blob1"`, `"link":"https://e.com"`, `"fields":[{"label":"Rating","value":"9"}]`} {
		if !strings.Contains(s, want) {
			t.Fatalf("missing %s in %s", want, s)
		}
	}
	if strings.Contains(s, "imageSrc") {
		t.Fatal("internal image source must not be serialized")
	}
}
