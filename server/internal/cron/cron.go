// Package cron parses five-field cron expressions (minute hour
// day-of-month month day-of-week) and a few friendly presets.
package cron

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

type Schedule struct{ min, hour, dom, month, dow uint64 }

var presets = map[string]string{
	"@hourly":  "0 * * * *",
	"@daily":   "0 9 * * *",
	"@weekly":  "0 9 * * 1",
	"@monthly": "0 9 1 * *",
}

func Parse(expr string) (*Schedule, error) {
	expr = strings.TrimSpace(expr)
	if p, ok := presets[expr]; ok {
		expr = p
	}
	f := strings.Fields(expr)
	if len(f) != 5 {
		return nil, fmt.Errorf("a schedule needs 5 fields (minute hour day month weekday), got %q", expr)
	}
	s := &Schedule{}
	var err error
	ranges := [5][2]int{{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7}}
	dst := []*uint64{&s.min, &s.hour, &s.dom, &s.month, &s.dow}
	for i, part := range f {
		if *dst[i], err = field(part, ranges[i][0], ranges[i][1]); err != nil {
			return nil, err
		}
	}
	if s.dow&(1<<7) != 0 { // 7 is Sunday too
		s.dow |= 1
	}
	return s, nil
}

func field(s string, lo, hi int) (uint64, error) {
	var bits uint64
	for _, part := range strings.Split(s, ",") {
		step := 1
		if i := strings.Index(part, "/"); i >= 0 {
			n, err := strconv.Atoi(part[i+1:])
			if err != nil || n < 1 {
				return 0, fmt.Errorf("bad step in %q", s)
			}
			step, part = n, part[:i]
		}
		a, b := lo, hi
		if part != "*" {
			if i := strings.Index(part, "-"); i >= 0 {
				var err1, err2 error
				a, err1 = strconv.Atoi(part[:i])
				b, err2 = strconv.Atoi(part[i+1:])
				if err1 != nil || err2 != nil {
					return 0, fmt.Errorf("bad range in %q", s)
				}
			} else {
				n, err := strconv.Atoi(part)
				if err != nil {
					return 0, fmt.Errorf("bad value in %q", s)
				}
				a, b = n, n
			}
		}
		if a < lo || b > hi || a > b {
			return 0, fmt.Errorf("%q is out of range %d-%d", s, lo, hi)
		}
		for v := a; v <= b; v += step {
			bits |= 1 << uint(v)
		}
	}
	return bits, nil
}

// Next returns the first matching minute strictly after t.
func (s *Schedule) Next(t time.Time) time.Time {
	t = t.Truncate(time.Minute).Add(time.Minute)
	for i := 0; i < 366*24*60; i++ {
		if s.month&(1<<uint(t.Month())) != 0 && s.dom&(1<<uint(t.Day())) != 0 &&
			s.dow&(1<<uint(t.Weekday())) != 0 && s.hour&(1<<uint(t.Hour())) != 0 && s.min&(1<<uint(t.Minute())) != 0 {
			return t
		}
		t = t.Add(time.Minute)
	}
	return t
}

// Describe gives a short human label for common schedules.
func Describe(expr string) string {
	f := strings.Fields(expr)
	if p, ok := presets[expr]; ok {
		f = strings.Fields(p)
	}
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
