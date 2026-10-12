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
