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

// TokenUsageDelta converts absolute cumulative prompt/completion counters into an
// increment since the previous Level-1 snapshot. Negative deltas (rare resets) clamp to 0.
// When prev is nil, returns 0/0 so the first snapshot only establishes a baseline —
// otherwise reconnects and mid-life discovery would count lifetime totals as "usage".
func TokenUsageDelta(prev *SessionSnapshot, prompt, completion int64) (dPrompt, dCompletion int64) {
	if prev == nil {
		return 0, 0
	}
	dPrompt = prompt - prev.PromptTokens
	dCompletion = completion - prev.CompletionTokens
	if dPrompt < 0 {
		dPrompt = 0
	}
	if dCompletion < 0 {
		dCompletion = 0
	}
	return dPrompt, dCompletion
}
