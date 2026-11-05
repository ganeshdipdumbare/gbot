package computer

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"time"
)

// The bot's browser is the window the person watches. gbot-web drives it
// over Chromium's DevTools protocol, one step per call: navigate, snapshot
// (numbered elements and the page text), click, type and press. Every step
// is run through the same exec path as the rest of the computer, so the
// person sees it in the Terminal, and the Activity log records it.

// webTimeout bounds one step: a navigation can take a while on a slow site.
const webTimeout = 90 * time.Second

// webCmd is the gbot-web command line for one step. Text and keys go after
// "--" so that something like "-h" is typed, not read as a flag.
func webCmd(step ...string) []string {
	return append([]string{"/usr/local/bin/gbot-web"}, step...)
}

func typeArgs(ref int, text string, submit bool) []string {
	a := []string{"type", strconv.Itoa(ref)}
	if submit {
		a = append(a, "--submit")
	}
	return append(a, "--", text)
}

func pressArgs(key string) []string { return []string{"press", "--", key} }

// webResult turns gbot-web's output into a tool result or an error. Exit
// status 127 means the computer doesn't have gbot-web at all: an image
// built before the browser tools existed.
func webResult(out, errOut string, code int) (string, error) {
	if code == 127 {
		return "", errors.New("browsing needs an updated computer image. Ask the owner to run make computer")
	}
	if code != 0 {
		msg := strings.TrimSpace(errOut)
		if msg == "" {
			msg = "the browser step failed"
		}
		return "", errors.New(msg)
	}
	return strings.TrimSpace(out), nil
}

// WebAction runs one browser step in the computer. what says it in the
// Activity log; it never includes typed text, which may be a password.
func (m *Manager) WebAction(ctx context.Context, o Owner, what string, args ...string) (string, error) {
	id, err := m.container(o, true)
	if err != nil {
		return "", err
	}
	m.note(ctx, id, "◍", "111", what)
	c, cancel := context.WithTimeout(ctx, webTimeout)
	defer cancel()
	out, errOut, code, err := m.d.exec(c, id, "gbot", []string{"DISPLAY=:1"}, nil, false, args...)
	if c.Err() != nil {
		return "", errors.New("the browser took too long to answer")
	}
	if err != nil {
		return "", err
	}
	return webResult(out, errOut, code)
}

func (m *Manager) WebNavigate(ctx context.Context, o Owner, url string) (string, error) {
	return m.WebAction(ctx, o, "Opening "+url, webCmd("navigate", "--", url)...)
}

func (m *Manager) WebSnapshot(ctx context.Context, o Owner) (string, error) {
	return m.WebAction(ctx, o, "Reading the page", webCmd("snapshot")...)
}

func (m *Manager) WebClick(ctx context.Context, o Owner, ref int) (string, error) {
	return m.WebAction(ctx, o, "Clicking ["+strconv.Itoa(ref)+"]", webCmd("click", strconv.Itoa(ref))...)
}

func (m *Manager) WebType(ctx context.Context, o Owner, ref int, text string, submit bool) (string, error) {
	return m.WebAction(ctx, o, "Typing into ["+strconv.Itoa(ref)+"]", webCmd(typeArgs(ref, text, submit)...)...)
}

func (m *Manager) WebPress(ctx context.Context, o Owner, key string) (string, error) {
	return m.WebAction(ctx, o, "Pressing "+key, webCmd(pressArgs(key)...)...)
}
