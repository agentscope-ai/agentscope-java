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
