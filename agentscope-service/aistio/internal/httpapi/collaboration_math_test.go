package httpapi

import "testing"

func TestArithmeticUsesExactValuesAndRejectsCode(t *testing.T) {
	for expr, want := range map[string]string{"17×23+6": "397", "(8+4)*13": "156", "0.1+0.2": "3/10", "9007199254740993+2": "9007199254740995", "101%7": "3", "-7/3": "-7/3"} {
		got, err := evaluateArithmetic(expr)
		if err != nil || got["result"] != want {
			t.Errorf("%s: %v %v", expr, got, err)
		}
	}
	for _, expr := range []string{"1/0", "1%0", "2.1%2", "os.Exit(0)", "1<<20", "1e999999999", "x+2"} {
		if got, err := evaluateArithmetic(expr); err == nil {
			t.Errorf("accepted %q: %v", expr, got)
		}
	}
}
