// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import "testing"

func TestSessionListArchiveFilter(t *testing.T) {
	cases := []struct {
		in      string
		want    string
		wantOK  bool
	}{
		{"", ` AND archived_at IS NULL`, true},
		{"active", ` AND archived_at IS NULL`, true},
		{"ACTIVE", ` AND archived_at IS NULL`, true},
		{"archived", ` AND archived_at IS NOT NULL`, true},
		{"all", ``, true},
		{"bogus", ``, false},
	}
	for _, tc := range cases {
		got, ok := sessionListArchiveFilter(tc.in)
		if ok != tc.wantOK || got != tc.want {
			t.Fatalf("status=%q got=(%q,%v) want=(%q,%v)", tc.in, got, ok, tc.want, tc.wantOK)
		}
	}
}
