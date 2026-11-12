// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Package cronexpr preserves Spring's six-field CronExpression contract,
// including macros, Sunday=0/7, AND day matching, L, W and # day modifiers.
package cronexpr

import (
	"fmt"
	"github.com/robfig/cron/v3"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"
)

type Schedule struct {
	base     cron.Schedule
	dom, dow func(time.Time) bool
}

func Parse(expression string) (*Schedule, error) {
	macros := map[string]string{"@yearly": "0 0 0 1 1 *", "@annually": "0 0 0 1 1 *", "@monthly": "0 0 0 1 * *", "@weekly": "0 0 0 * * 0", "@daily": "0 0 0 * * *", "@midnight": "0 0 0 * * *", "@hourly": "0 0 * * * *"}
	expression = strings.TrimSpace(expression)
	if v, ok := macros[strings.ToLower(expression)]; ok {
		expression = v
	}
	fields := strings.Fields(strings.ToUpper(expression))
	if len(fields) != 6 {
		return nil, fmt.Errorf("cron requires six fields")
	}
	for _, index := range []int{0, 1, 2, 4} {
		if strings.Contains(fields[index], "?") {
			return nil, fmt.Errorf("question mark is only supported in day fields")
		}
	}
	dom, err := days(fields[3], false)
	if err != nil {
		return nil, err
	}
	dow, err := days(fields[5], true)
	if err != nil {
		return nil, err
	}
	fields[3], fields[5] = "*", "*"
	base, err := cron.NewParser(cron.Second | cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow).Parse(strings.Join(fields, " "))
	if err != nil {
		return nil, err
	}
	return &Schedule{base, dom, dow}, nil
}
func (s *Schedule) Next(after time.Time) time.Time {
	limit := after.AddDate(400, 0, 0)
	for next := s.base.Next(after); !next.IsZero() && next.Before(limit); next = s.base.Next(next) {
		if s.dom(next) && s.dow(next) {
			return next
		}
		boundary := time.Date(next.Year(), next.Month(), next.Day()+1, 0, 0, 0, 0, next.Location())
		// Some zones jump over midnight. time.Date can normalize that instant
		// back into the rejected day; always advance beyond it before asking
		// the base schedule for another candidate.
		for !boundary.After(next) || (boundary.Year() == next.Year() && boundary.YearDay() == next.YearDay()) {
			boundary = boundary.Add(time.Minute)
		}
		next = boundary.Add(-time.Second)
	}
	return time.Time{}
}
func days(field string, dow bool) (func(time.Time) bool, error) {
	names := []string{"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"}
	if dow {
		for i, n := range names {
			day := i
			if i == 0 {
				day = 7
			}
			field = strings.ReplaceAll(field, n, strconv.Itoa(day))
		}
	}
	if field == "*" || field == "?" {
		return func(time.Time) bool { return true }, nil
	}
	tests := []func(time.Time) bool{}
	for _, part := range strings.Split(field, ",") {
		switch {
		case !dow && part == "LW":
			tests = append(tests, func(t time.Time) bool { return t.Day() == weekday(t, last(t)) })
		case !dow && strings.HasPrefix(part, "L"):
			offset := 0
			if part != "L" {
				var e error
				offset, e = strconv.Atoi(strings.TrimPrefix(part, "L-"))
				if e != nil || !strings.HasPrefix(part, "L-") || offset <= 0 || offset > 30 {
					return nil, fmt.Errorf("invalid last-day offset")
				}
			}
			tests = append(tests, func(t time.Time) bool { return t.Day() == last(t)-offset })
		case !dow && strings.HasSuffix(part, "W"):
			n, e := strconv.Atoi(strings.TrimSuffix(part, "W"))
			if e != nil || n < 1 || n > 31 {
				return nil, fmt.Errorf("invalid weekday")
			}
			tests = append(tests, func(t time.Time) bool { return n <= last(t) && t.Day() == weekday(t, n) })
		case dow && (strings.HasSuffix(part, "L") || strings.Contains(part, "#")):
			var d, n int
			var e error
			isLast := strings.HasSuffix(part, "L")
			if isLast {
				d, e = strconv.Atoi(strings.TrimSuffix(part, "L"))
			} else {
				pieces := strings.Split(part, "#")
				if len(pieces) != 2 {
					return nil, fmt.Errorf("invalid nth weekday")
				}
				d, e = strconv.Atoi(pieces[0])
				n, _ = strconv.Atoi(pieces[1])
			}
			if e != nil || d < 0 || d > 7 || !isLast && (n < 1 || n > 5) {
				return nil, fmt.Errorf("invalid weekday modifier")
			}
			d %= 7
			tests = append(tests, func(t time.Time) bool {
				return int(t.Weekday()) == d && (isLast && t.Day()+7 > last(t) || !isLast && (t.Day()-1)/7+1 == n)
			})
		default:
			min, max := 1, 31
			if dow {
				min, max = 0, 7
			}
			allowed := map[int]bool{}
			steps := strings.Split(part, "/")
			if len(steps) > 2 {
				return nil, fmt.Errorf("invalid day step")
			}
			step := 1
			if len(steps) == 2 {
				var e error
				step, e = strconv.Atoi(steps[1])
				if e != nil || step < 1 {
					return nil, fmt.Errorf("invalid day step")
				}
			}
			start, end := min, max
			if steps[0] != "*" && steps[0] != "?" {
				bounds := strings.Split(steps[0], "-")
				var e error
				start, e = strconv.Atoi(bounds[0])
				if e != nil {
					return nil, e
				}
				end = start
				if len(bounds) == 2 {
					end, e = strconv.Atoi(bounds[1])
					if e != nil {
						return nil, e
					}
				} else if len(bounds) > 2 {
					return nil, fmt.Errorf("invalid range")
				} else if len(steps) == 2 {
					end = max
				}
			}
			if start < min || start > max || end < min || end > max {
				return nil, fmt.Errorf("invalid day range")
			}
			if dow && start == 7 && (strings.Contains(steps[0], "-") || len(steps) == 2) {
				start = 0
			}
			if start > end {
				return nil, fmt.Errorf("invalid day range")
			}

			for n := start; n <= end; n += step {
				if dow {
					allowed[n%7] = true
				} else {
					allowed[n] = true
				}
			}
			tests = append(tests, func(t time.Time) bool {
				n := t.Day()
				if dow {
					n = int(t.Weekday())
				}
				return allowed[n]
			})
		}
	}
	return func(t time.Time) bool {
		for _, test := range tests {
			if test(t) {
				return true
			}
		}
		return false
	}, nil
}
func last(t time.Time) int {
	return time.Date(t.Year(), t.Month()+1, 0, 12, 0, 0, 0, t.Location()).Day()
}
func weekday(t time.Time, day int) int {
	d := time.Date(t.Year(), t.Month(), day, 12, 0, 0, 0, t.Location())
	switch d.Weekday() {
	case time.Saturday:
		if day == 1 {
			return 3
		}
		return day - 1
	case time.Sunday:
		if day == last(t) {
			return day - 2
		}
		return day + 1
	}
	return day
}
