// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package cronexpr

import (
	"testing"
	"time"
)

func TestSpringCronCompatibility(t *testing.T) {
	for _, tc := range []struct{ expression, anchor, want string }{
		{"*/10 * * * * *", "2026-10-16T01:01:07Z", "2026-10-16T01:01:10Z"},
		{"0 0 9 * * MON-FRI", "2026-10-16T09:00:00Z", "2026-10-19T09:00:00Z"},
		{"0 0 0 * * 7", "2026-10-16T00:00:00Z", "2026-10-18T00:00:00Z"},
		{"0 0 0 1 * MON", "2026-10-16T00:00:00Z", "2027-02-01T00:00:00Z"},
		{"0 0 0 L * ?", "2026-02-01T00:00:00Z", "2026-02-28T00:00:00Z"},
		{"0 0 0 L-2 * ?", "2026-02-01T00:00:00Z", "2026-02-26T00:00:00Z"},
		{"0 0 0 LW * *", "2026-02-01T00:00:00Z", "2026-02-27T00:00:00Z"},
		{"0 0 0 1W * *", "2026-01-31T00:00:00Z", "2026-02-02T00:00:00Z"},
		{"0 0 0 ? * 5L", "2026-10-01T00:00:00Z", "2026-10-30T00:00:00Z"},
		{"0 0 0 ? * MON#2", "2026-10-01T00:00:00Z", "2026-10-12T00:00:00Z"},
		{"@monthly", "2026-10-16T00:00:00Z", "2026-11-01T00:00:00Z"},
		{"0 0 0 29 2 *", "2026-10-16T00:00:00Z", "2028-02-29T00:00:00Z"},
		{"0 0 0 29 2 MON", "2026-10-16T00:00:00Z", "2044-02-29T00:00:00Z"},
		{"0 0 0 * * MON-SUN", "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z"},
		{"0 0 0 * * 7-6", "2026-10-16T00:00:00Z", "2026-10-17T00:00:00Z"},
	} {
		t.Run(tc.expression, func(t *testing.T) {
			s, err := Parse(tc.expression)
			if err != nil {
				t.Fatal(err)
			}
			anchor, _ := time.Parse(time.RFC3339, tc.anchor)
			if got := s.Next(anchor).Format(time.RFC3339); got != tc.want {
				t.Fatalf("got %s want %s", got, tc.want)
			}
		})
	}
}
func TestInvalidSpringCron(t *testing.T) {
	for _, expr := range []string{"* * * * *", "0 0 0 L-40 * *", "0 0 0 32W * *", "0 0 0 * * MON#6", "0 0 0 * * 8", "0 0 25 * * *", "0 0 0 */0 * *", "0 0 0 L-0 * ?", "? * * * * *", "0 0 0 * * 1-0"} {
		if _, err := Parse(expr); err == nil {
			t.Errorf("accepted %q", expr)
		}
	}
}
func TestSpringCronTimezoneAndDST(t *testing.T) {
	loc, err := time.LoadLocation("America/New_York")
	if err != nil {
		t.Fatal(err)
	}
	s, _ := Parse("0 30 2 * * *")
	anchor := time.Date(2026, 3, 7, 3, 0, 0, 0, loc)
	want := time.Date(2026, 3, 9, 2, 30, 0, 0, loc)
	if got := s.Next(anchor); !got.Equal(want) {
		t.Fatalf("DST got %s want %s", got, want)
	}
}

func TestSpringCronSkipsRejectedDayAcrossMidnightDST(t *testing.T) {
	loc, err := time.LoadLocation("America/Santiago")
	if err != nil {
		t.Fatal(err)
	}
	schedule, err := Parse("0 0 * 7 9 *")
	if err != nil {
		t.Fatal(err)
	}
	anchor := time.Date(2026, 9, 5, 0, 0, 0, 0, loc)
	want := time.Date(2026, 9, 7, 0, 0, 0, 0, loc)
	if got := schedule.Next(anchor); !got.Equal(want) {
		t.Fatalf("midnight DST got %s want %s", got, want)
	}
}
