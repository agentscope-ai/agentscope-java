package store

import "testing"

func TestTokenUsageDelta(t *testing.T) {
	t.Parallel()

	dP, dC := TokenUsageDelta(nil, 100, 20)
	if dP != 0 || dC != 0 {
		t.Fatalf("first observation baselines only: got %d/%d", dP, dC)
	}

	prev := &SessionSnapshot{PromptTokens: 100, CompletionTokens: 20}
	dP, dC = TokenUsageDelta(prev, 150, 35)
	if dP != 50 || dC != 15 {
		t.Fatalf("increment: got %d/%d", dP, dC)
	}

	dP, dC = TokenUsageDelta(prev, 100, 20)
	if dP != 0 || dC != 0 {
		t.Fatalf("unchanged: got %d/%d", dP, dC)
	}

	dP, dC = TokenUsageDelta(prev, 80, 10)
	if dP != 0 || dC != 0 {
		t.Fatalf("reset clamp: got %d/%d", dP, dC)
	}
}
