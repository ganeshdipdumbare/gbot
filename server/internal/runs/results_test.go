package runs

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestResultRowsReadsListAnswer(t *testing.T) {
	title, rows := resultRows([]byte(`[{"name":"A"},{"name":"B"}]`))
	if title != "" || len(rows) != 2 {
		t.Fatalf("list answer: got title %q, %d rows", title, len(rows))
	}
}

func TestResultRowsFindsListInObject(t *testing.T) {
	title, rows := resultRows([]byte(`{"title":"Hotels","results":[{"name":"A"},{"name":"B"}]}`))
	if title != "Hotels" || len(rows) != 2 {
		t.Fatalf("object answer: got title %q, %d rows", title, len(rows))
	}
}

func TestResultRowsIgnoresPlainText(t *testing.T) {
	if _, rows := resultRows([]byte(`not json`)); rows != nil {
		t.Fatalf("plain text should give no rows, got %d", len(rows))
	}
}

func TestCardFromRowMapsFieldsAndDropsUnsafeLinks(t *testing.T) {
	row := map[string]any{
		"name":            "Echo Hotel and Spa",
		"location":        "The Mall",
		"rating":          9.6,
		"price_per_night": "$33.50",
		"booked":          true,
		"url":             "javascript:alert(1)",
		"image":           "http://example.com/a.jpg",
		"nested":          map[string]any{"x": 1},
	}
	card, ok := cardFromRow(row)
	if !ok {
		t.Fatal("row with a title should make a card")
	}
	if card.Title != "Echo Hotel and Spa" || card.Subtitle != "The Mall" {
		t.Fatalf("title/subtitle: %+v", card)
	}
	if card.Link != "" {
		t.Fatalf("javascript: link must be dropped, got %q", card.Link)
	}
	if card.imageSrc != "" {
		t.Fatalf("http image must not be fetched, got %q", card.imageSrc)
	}
	got := map[string]string{}
	for _, f := range card.Fields {
		got[f.Label] = f.Value
	}
	if got["Rating"] != "9.6" || got["Price per night"] != "$33.50" || got["Booked"] != "yes" {
		t.Fatalf("fields: %+v", card.Fields)
	}
	if _, ok := got["Nested"]; ok {
		t.Fatal("nested objects must not become fields")
	}
}

func TestCardFromRowNeedsTitle(t *testing.T) {
	if _, ok := cardFromRow(map[string]any{"price": "$5"}); ok {
		t.Fatal("a row without a title should not make a card")
	}
}

func TestCardFromRowHttpsLinkAndImage(t *testing.T) {
	card, _ := cardFromRow(map[string]any{"title": "X", "url": "https://example.com/x", "thumbnail": "https://example.com/x.jpg"})
	if card.Link != "https://example.com/x" || card.imageSrc != "https://example.com/x.jpg" {
		t.Fatalf("https link/image: %+v", card)
	}
}

func TestCardFieldLimit(t *testing.T) {
	row := map[string]any{"title": "X"}
	for _, k := range []string{"a", "b", "c", "d", "e", "f", "g", "h"} {
		row[k] = k
	}
	card, _ := cardFromRow(row)
	if len(card.Fields) != maxResultFields {
		t.Fatalf("fields should be capped at %d, got %d", maxResultFields, len(card.Fields))
	}
}

func TestClipKeepsRunesWhole(t *testing.T) {
	long := strings.Repeat("é", 300)
	out := clipText(long)
	if len([]rune(out)) != maxResultText {
		t.Fatalf("clip length: %d runes", len([]rune(out)))
	}
}

func TestSafeLink(t *testing.T) {
	cases := map[string]bool{
		"https://example.com/a": true,
		"http://example.com/a":  false,
		"javascript:alert(1)":   false,
		"data:text/html,x":      false,
		"":                      false,
	}
	for in, want := range cases {
		if (safeLink(in) != "") != want {
			t.Errorf("safeLink(%q): want ok=%v", in, want)
		}
	}
}

func TestHumanize(t *testing.T) {
	if got := humanize("price_per_night"); got != "Price per night" {
		t.Fatalf("humanize: %q", got)
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
