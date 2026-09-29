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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package anthropiccli

import (
	"strings"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestDescriptorUsesClaudeNativeSkillsDirectory(t *testing.T) {
	descriptor := (&Adapter{}).Descriptor()
	if !descriptor.Skills.Supported || descriptor.Skills.Mode != "native-directory" ||
		descriptor.Skills.Target != ".claude/skills" {
		t.Fatalf("skills capability = %+v", descriptor.Skills)
	}
}

func TestBuildArgs(t *testing.T) {
	args := buildArgs(provider.Request{ProviderSessionID: "session-1"}, configuration{
		Model: "sonnet", PermissionMode: "acceptEdits", AllowedTools: []string{"Read", "Bash(git diff:*)"},
		DisallowedTools: []string{"Bash(git push:*)"}, MaxTurns: 12, AppendSystemPrompt: "Follow repository rules.",
	}, "")
	got := strings.Join(args, " ")
	want := "-p --output-format stream-json --verbose --resume session-1 --model sonnet " +
		"--permission-mode acceptEdits --allowedTools Read,Bash(git diff:*) " +
		"--disallowedTools Bash(git push:*) --max-turns 12 --append-system-prompt Follow repository rules."
	if got != want {
		t.Fatalf("args=%q, want %q", got, want)
	}
}

func TestBuildArgsAdaptsPortableDefinition(t *testing.T) {
	args := buildArgs(provider.Request{Definition: &provider.AgentDefinition{
		Model: "agent-model", System: "Agent instructions.",
	}}, configuration{Model: "profile-model", AppendSystemPrompt: "Profile guardrails."}, "")
	got := strings.Join(args, " ")
	if !strings.Contains(got, "--model agent-model") || strings.Contains(got, "profile-model") ||
		!strings.Contains(got, "Profile guardrails.\n\nAgent instructions.") {
		t.Fatalf("args=%q", got)
	}
}

func TestConsumeJSONL(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"system","subtype":"init","session_id":"session-1"}`,
		`{"type":"assistant","session_id":"session-1","message":{"content":[{"type":"text","text":"working"}]}}`,
		`{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"session-1"}`,
	}, "\n")
	result := &provider.Result{}
	var events []string
	if err := consumeJSONL(strings.NewReader(input), result, func(event provider.Event) error {
		events = append(events, event.Type)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if result.ProviderSessionID != "session-1" || result.Output != "done" || len(events) != 3 {
		t.Fatalf("result=%+v events=%v", result, events)
	}
}

func TestConsumeJSONLErrorResult(t *testing.T) {
	input := `{"type":"result","subtype":"error_max_turns","is_error":true,"result":"stopped","session_id":"session-2"}`
	if err := consumeJSONL(strings.NewReader(input), &provider.Result{}, nil); err == nil {
		t.Fatal("expected error result")
	}
}
