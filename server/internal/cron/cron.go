// Package cron turns a routine's schedule into the next time it runs.
// The five-field parsing is kritrim's (trigger.ParseCron), which also
// takes month and weekday names and follows Vixie cron's rule for the
// two day fields. This package keeps what is gbot's own: friendlier
// presets, and the short labels a routine shows in the app.
package cron

import (
	"strconv"
	"strings"
	"time"

	"github.com/ganeshdipdumbare/kritrim/trigger"
)

// presets are gbot's, and deliberately not kritrim's: a bot's daily
// routine reports to a person, so it runs at 9am rather than midnight.
// These names shadow the kritrim descriptors of the same name, so a
// routine made before this kept its time. @yearly, @annually and
// @midnight fall through to kritrim, where midnight is what they mean.
var presets = map[string]string{
	"@hourly":  "0 * * * *",
	"@daily":   "0 9 * * *",
	"@weekly":  "0 9 * * 1",
	"@monthly": "0 9 1 * *",
}

// Schedule is a parsed schedule.
type Schedule struct{ c trigger.Cron }

// Parse reads a five-field cron expression or one of the presets.
func Parse(expr string) (*Schedule, error) {
	c, err := trigger.ParseCron(expand(expr))
	if err != nil {
		return nil, err
	}
	return &Schedule{c: c}, nil
}

// Next returns the first matching minute strictly after t, in t's
// location. It returns the zero time when the schedule never comes
// round again (February 30, say), so callers must check it.
func (s *Schedule) Next(t time.Time) time.Time { return s.c.Next(t) }

// expand replaces a gbot preset with the expression behind it.
func expand(expr string) string {
	expr = strings.TrimSpace(expr)
	if p, ok := presets[strings.ToLower(expr)]; ok {
		return p
	}
	return expr
}

// Describe gives a short human label for common schedules.
func Describe(expr string) string {
	f := strings.Fields(expand(expr))
	if len(f) != 5 {
		return expr
	}
	at := func() string {
		h, _ := strconv.Atoi(f[1])
		m, _ := strconv.Atoi(f[0])
		return time.Date(2000, 1, 1, h, m, 0, 0, time.Local).Format("3:04 PM")
	}
	plainTime := !strings.ContainsAny(f[0]+f[1], "*,/-")
	switch {
	case f[0] == "0" && f[1] == "*" && f[2] == "*" && f[3] == "*" && f[4] == "*":
		return "Every hour"
	case plainTime && f[2] == "*" && f[3] == "*" && f[4] == "*":
		return "Every day at " + at()
	case plainTime && f[2] == "*" && f[3] == "*" && f[4] == "1-5":
		return "Weekdays at " + at()
	case plainTime && f[2] == "*" && f[3] == "*" && len(f[4]) == 1:
		d, _ := strconv.Atoi(f[4])
		return time.Weekday(d%7).String() + "s at " + at()
	case plainTime && f[2] == "1" && f[3] == "*" && f[4] == "*":
		return "Monthly on the 1st at " + at()
	}
	return expr
}
