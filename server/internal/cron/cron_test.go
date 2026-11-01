package cron

import (
	"testing"
	"time"
)

func TestNext(t *testing.T) {
	base := time.Date(2026, 10, 30, 8, 30, 0, 0, time.UTC) // a Friday
	cases := []struct{ expr, want string }{
		{"0 9 * * *", "2026-10-30 09:00"},
		{"*/15 * * * *", "2026-10-30 08:45"},
		{"0 9 * * 1-5", "2026-10-30 09:00"},
		{"0 8 * * 1-5", "2026-11-02 08:00"},
		{"0 9 1 * *", "2026-11-01 09:00"},
		{"30 8 * * 0", "2026-11-01 08:30"},
		{"30 8 * * 7", "2026-11-01 08:30"},
		{"@hourly", "2026-10-30 09:00"},
	}
	for _, c := range cases {
		s, err := Parse(c.expr)
		if err != nil {
			t.Fatalf("%s: %v", c.expr, err)
		}
		if got := s.Next(base).Format("2006-01-02 15:04"); got != c.want {
			t.Errorf("%s: got %s, want %s", c.expr, got, c.want)
		}
	}
}

func TestParseErrors(t *testing.T) {
	for _, bad := range []string{"", "* * * *", "61 * * * *", "* 24 * * *", "a * * * *", "*/0 * * * *", "5-1 * * * *"} {
		if _, err := Parse(bad); err == nil {
			t.Errorf("%q should fail", bad)
		}
	}
}

func TestDescribe(t *testing.T) {
	for expr, want := range map[string]string{
		"0 * * * *":   "Every hour",
		"0 8 * * 1-5": "Weekdays at 8:00 AM",
		"30 18 * * *": "Every day at 6:30 PM",
		"0 9 * * 1":   "Mondays at 9:00 AM",
		"*/5 * * * *": "*/5 * * * *",
	} {
		if got := Describe(expr); got != want {
			t.Errorf("%s: got %q, want %q", expr, got, want)
		}
	}
}
