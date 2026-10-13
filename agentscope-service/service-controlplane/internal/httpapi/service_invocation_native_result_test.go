/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import "testing"

func TestNativeServiceTurnAnswerUsesCanonicalRoleAndFinalOutput(t *testing.T) {
	item := func(turn, role, text string, final bool) any {
		return map[string]any{"data": map[string]any{"turn_id": turn, "final_output": final, "item": map[string]any{
			"role": role, "content": []any{map[string]any{"type": "text", "text": text}},
		}}}
	}
	for _, final := range []string{"complete reply", ""} {
		snapshot := map[string]any{"items": []any{
			item("this", "ASSISTANT", "reasoning", false),
			item("this", "ASSISTANT", final, true),
			item("this", "assistant", "late intermediate", false),
			item("other", "ASSISTANT", "other turn", true),
			item("this", "USER", "question", true),
			item("this", "TOOL", "tool output", true),
		}}
		if got := nativeServiceTurnAnswer(snapshot, "this"); got != final {
			t.Fatalf("got %q, want %q", got, final)
		}
	}
}
