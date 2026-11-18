package runs

// Result cards: when a tool hands back a list of records (hotels, products,
// places), the chat shows them as cards. The model still reads the text of
// the answer. The cards are a second view for the person, built only from
// the structured data, so the model's prose can't invent a link or a picture
// on the page: links must be https, and images are fetched by gbot and kept
// as blobs, the same as tool pictures.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"gbot/internal/netguard"
	"gbot/internal/store"
)

const (
	maxResultCards  = 12
	maxResultFields = 6
	maxResultText   = 160
	maxResultLink   = 2000
)

// resultCard is one record, as the chat draws it.
type resultCard struct {
	Title    string        `json:"title"`
	Subtitle string        `json:"subtitle,omitempty"`
	Image    string        `json:"image,omitempty"` // blob id, served by /api/blobs
	Mime     string        `json:"mime,omitempty"`
	Link     string        `json:"link,omitempty"` // https only
	Fields   []resultField `json:"fields,omitempty"`

	imageSrc string // remote image to fetch; never sent to the chat
}

type resultField struct {
	Label string `json:"label"`
	Value string `json:"value"`
}

// resultSet is one tool answer that became cards, waiting for the chat.
type resultSet struct {
	From  string
	Title string
	Items []resultCard
}

func (rc *runCtx) addResults(s resultSet) {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	rc.results = append(rc.results, s)
}

// takeResults hands over what is waiting; each set is shown once.
func (rc *runCtx) takeResults() []resultSet {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	out := rc.results
	rc.results = nil
	return out
}

// keepResults reads a tool's structured answer as a list of cards and keeps
// it for the chat. It returns a line for the model when cards were kept, so
// the model knows they are there, or "" when the answer is not a list.
func (r *Runtime) keepResults(ctx context.Context, server string, raw []byte) string {
	rc := runFrom(ctx)
	if rc == nil || len(raw) == 0 {
		return ""
	}
	title, rows := resultRows(raw)
	if len(rows) < 2 {
		return ""
	}
	if len(rows) > maxResultCards {
		rows = rows[:maxResultCards]
	}
	set := resultSet{From: server, Title: title}
	for _, row := range rows {
		card, ok := cardFromRow(row)
		if !ok {
			continue
		}
		if card.imageSrc != "" {
			card.Image, card.Mime = r.keepImage(ctx, rc, card.imageSrc)
		}
		set.Items = append(set.Items, card)
	}
	if len(set.Items) < 2 {
		return ""
	}
	rc.addResults(set)
	return fmt.Sprintf("[%d results from %s are in the chat as cards, where you are answering. The person can see them. Summarize what matters; don't list every row again.]", len(set.Items), server)
}

// keepImage fetches a remote thumbnail through the guarded client and keeps
// it as a blob. Anything that is not a plain https picture is skipped.
func (r *Runtime) keepImage(ctx context.Context, rc *runCtx, src string) (string, string) {
	if netguard.Endpoint(src) != nil {
		return "", ""
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, src, nil)
	if err != nil {
		return "", ""
	}
	resp, err := netguard.Client(15 * time.Second).Do(req)
	if err != nil {
		return "", ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", ""
	}
	mime := strings.TrimSpace(strings.Split(resp.Header.Get("Content-Type"), ";")[0])
	if !showableImage(mime) {
		return "", ""
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, store.MaxBlob+1))
	if err != nil || len(raw) == 0 || len(raw) > store.MaxBlob {
		return "", ""
	}
	id, err := r.store.AddBlob(context.WithoutCancel(ctx), &store.Blob{BotID: rc.botID, User: rc.userID, Mime: mime, Bytes: raw})
	if err != nil {
		r.log.Error("save result image", "err", err)
		return "", ""
	}
	return id, mime
}

// resultRows finds the list of records in a structured answer: the answer
// itself when it is a list, or the first list of records in an object.
func resultRows(raw []byte) (string, []map[string]any) {
	var v any
	if json.Unmarshal(raw, &v) != nil {
		return "", nil
	}
	switch t := v.(type) {
	case []any:
		return "", objectsOf(t)
	case map[string]any:
		title, _ := t["title"].(string)
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			if arr, ok := t[k].([]any); ok {
				if rows := objectsOf(arr); len(rows) > 0 {
					return clipText(title), rows
				}
			}
		}
	}
	return "", nil
}

func objectsOf(a []any) []map[string]any {
	var out []map[string]any
	for _, x := range a {
		if m, ok := x.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

var (
	titleKeys    = []string{"title", "name", "label"}
	subtitleKeys = []string{"subtitle", "location", "address", "city", "description", "summary"}
	linkKeys     = []string{"url", "link", "href"}
	imageKeys    = []string{"image", "image_url", "imageUrl", "thumbnail", "thumbnail_url", "photo", "picture"}
)

// cardFromRow maps one record to a card. A record needs a title; every
// other field is optional. The fields not used elsewhere become label and
// value lines, in key order, up to a fixed count.
func cardFromRow(row map[string]any) (resultCard, bool) {
	used := map[string]bool{}
	pick := func(keys ...string) string {
		for _, k := range keys {
			if s := scalarText(row[k]); s != "" {
				used[k] = true
				return s
			}
		}
		return ""
	}
	title := pick(titleKeys...)
	if title == "" {
		return resultCard{}, false
	}
	card := resultCard{Title: clipText(title), Subtitle: clipText(pick(subtitleKeys...))}
	for _, k := range linkKeys {
		if link := safeLink(scalarText(row[k])); link != "" {
			card.Link = link
			used[k] = true
			break
		}
	}
	for _, k := range imageKeys {
		if src := safeLink(scalarText(row[k])); src != "" {
			card.imageSrc = src
			used[k] = true
			break
		}
	}
	keys := make([]string, 0, len(row))
	for k := range row {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if used[k] || len(card.Fields) == maxResultFields {
			continue
		}
		if val := scalarText(row[k]); val != "" {
			card.Fields = append(card.Fields, resultField{Label: humanize(k), Value: clipText(val)})
		}
	}
	return card, true
}

// safeLink returns s if it is an absolute https URL, or "" otherwise. A
// javascript:, data: or plain http link is never shown.
func safeLink(s string) string {
	if s == "" || len(s) > maxResultLink {
		return ""
	}
	u, err := url.Parse(s)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return ""
	}
	return u.String()
}

// scalarText is a value as a short line of text. Nested objects and lists
// and empty values give "".
func scalarText(v any) string {
	switch t := v.(type) {
	case string:
		return strings.TrimSpace(t)
	case float64:
		return strconv.FormatFloat(t, 'f', -1, 64)
	case bool:
		if t {
			return "yes"
		}
		return "no"
	}
	return ""
}

// humanize turns a key like price_per_night into "Price per night".
func humanize(k string) string {
	s := strings.ReplaceAll(strings.ReplaceAll(k, "_", " "), "-", " ")
	s = strings.TrimSpace(s)
	if s == "" {
		return k
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

// clip shortens a line to a fixed length, on a rune boundary.
func clipText(s string) string {
	s = strings.TrimSpace(s)
	r := []rune(s)
	if len(r) <= maxResultText {
		return s
	}
	return string(r[:maxResultText-1]) + "…"
}

// showResults puts each set of cards into the chat as its own row, where
// the person is watching, the same way pictures are shown.
func (r *Runtime) showResults(ctx context.Context, c convo, b *store.Bot, rc *runCtx) {
	if rc == nil {
		return
	}
	for _, set := range rc.takeResults() {
		from := set.From
		if from == "" {
			from = "a tool"
		}
		title := set.Title
		if title == "" {
			title = "Results from " + from
		}
		m := &store.Message{BotID: b.ID, GroupID: c.GroupID, ChatUser: c.User, Role: "event",
			Content: title,
			Data:    map[string]any{"kind": "results", "from": from, "title": title, "items": set.Items}}
		if err := r.store.AddMessage(ctx, m, nil); err != nil {
			r.log.Error("show results", "err", err)
			continue
		}
		r.chat(c, Event{Type: "message", BotID: b.ID, GroupID: c.GroupID, Data: m})
	}
}
