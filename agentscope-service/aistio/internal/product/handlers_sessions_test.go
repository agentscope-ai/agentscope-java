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
