package runs

// Result cards: when the bot has a list of things to compare (hotels,
// products, places), it hands the list to show_results and the chat shows
// them as cards. The bot's prose still says what matters. The cards are a
// second view for the person, built only from what the bot passed in, so
// a link is always https, and images are fetched by gbot and kept as blobs,
// the same as tool pictures.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
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

// resultSet is one list of cards, waiting for the chat.
type resultSet struct {
	Title string
	Items []resultCard
}

// resultsIn is the list the bot hands over to be shown as cards.
type resultsIn struct {
	Title string       `json:"title,omitempty" desc:"a short heading for the list, e.g. 'Hotels in Darjeeling, 9-13 Dec'"`
	Items []resultItem `json:"items" desc:"2 to 12 records, each taken from a tool answer you have read"`
}

type resultItem struct {
	Title    string       `json:"title" desc:"the name of the thing"`
	Subtitle string       `json:"subtitle,omitempty" desc:"a place, a date or a short description"`
	Link     string       `json:"link,omitempty" desc:"an https page the person can open, exactly as a tool gave it"`
	Image    string       `json:"image,omitempty" desc:"an https picture URL, exactly as a tool gave it"`
	Fields   []resultLine `json:"fields,omitempty" desc:"up to 6 label and value lines, e.g. price, rating, dates"`
}

type resultLine struct {
	Label string `json:"label" desc:"e.g. Price"`
	Value string `json:"value" desc:"e.g. $33.50 a night"`
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

// showResultsTool is the show_results tool the bot calls. It keeps the list
// for the chat and returns a line for the model.
func (r *Runtime) showResultsTool(ctx context.Context, in resultsIn) (string, error) {
	rc := runFrom(ctx)
	if rc == nil {
		return "", errors.New("no run")
	}
	set, ok := resultSetFrom(in)
	if !ok {
		return "", errors.New("give at least two items, each with a title")
	}
	r.keepImages(ctx, rc, set.Items)
	rc.addResults(set)
	return fmt.Sprintf("Shown %d results as cards in the chat, where you are answering. The person can see them. Summarize what matters; don't list every row again.", len(set.Items)), nil
}

// resultSetFrom checks and caps what the bot passed in. It needs at least
// two cards with a title, so one item is just a sentence, not a list.
func resultSetFrom(in resultsIn) (resultSet, bool) {
	set := resultSet{Title: clipText(in.Title)}
	for i, it := range in.Items {
		if i == maxResultCards {
			break
		}
		if card, ok := cardFromItem(it); ok {
			set.Items = append(set.Items, card)
		}
	}
	return set, len(set.Items) >= 2
}

// cardFromItem maps one item to a card. Unsafe links and images are dropped
// rather than failing the whole list.
func cardFromItem(it resultItem) (resultCard, bool) {
	title := clipText(it.Title)
	if title == "" {
		return resultCard{}, false
	}
	card := resultCard{
		Title:    title,
		Subtitle: clipText(it.Subtitle),
		Link:     safeLink(it.Link),
		imageSrc: safeLink(it.Image),
	}
	for _, f := range it.Fields {
		if len(card.Fields) == maxResultFields {
			break
		}
		label, value := clipText(f.Label), clipText(f.Value)
		if label == "" || value == "" {
			continue
		}
		card.Fields = append(card.Fields, resultField{Label: label, Value: value})
	}
	return card, true
}

// keepImages fetches the card thumbnails side by side, so one slow host
// can't hold the answer for the sum of all of them.
func (r *Runtime) keepImages(ctx context.Context, rc *runCtx, cards []resultCard) {
	var wg sync.WaitGroup
	for i := range cards {
		if src := cards[i].imageSrc; src != "" {
			wg.Add(1)
			go func(c *resultCard) {
				defer wg.Done()
				c.Image, c.Mime = r.keepImage(ctx, rc, src)
			}(&cards[i])
		}
	}
	wg.Wait()
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
	// One picture per connection: nothing reuses it, so don't leave it idle.
	req.Close = true
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

// safeLink returns s if it is an absolute https URL, or "" otherwise. A
// javascript:, data: or plain http link is never shown.
func safeLink(s string) string {
	s = strings.TrimSpace(s)
	if s == "" || len(s) > maxResultLink {
		return ""
	}
	u, err := url.Parse(s)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return ""
	}
	return u.String()
}

// clipText shortens a line to a fixed length, on a rune boundary.
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
		title := set.Title
		if title == "" {
			title = "Results"
		}
		m := &store.Message{BotID: b.ID, GroupID: c.GroupID, ChatUser: c.User, Role: "event",
			Content: title,
			Data:    map[string]any{"kind": "results", "title": title, "items": set.Items}}
		if err := r.store.AddMessage(ctx, m, nil); err != nil {
			r.log.Error("show results", "err", err)
			continue
		}
		r.chat(c, Event{Type: "message", BotID: b.ID, GroupID: c.GroupID, Data: m})
	}
}
