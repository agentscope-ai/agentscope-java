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

package codex

import (
	"strings"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestBuildArgsNewSession(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work"}, configuration{Model: "gpt-test", SkipGitRepoCheck: true})
	got := strings.Join(args, " ")
	want := "exec --json --model gpt-test --skip-git-repo-check --sandbox workspace-write --cd /tmp/work -"
	if got != want {
		t.Fatalf("args=%q, want %q", got, want)
	}
}

func TestBuildArgsResume(t *testing.T) {
	args := buildArgs(provider.Request{Workspace: "/tmp/work", ProviderSessionID: "thread-1"}, configuration{})
	got := strings.Join(args, " ")
	if got != "exec resume --json thread-1 -" {
		t.Fatalf("args=%q", got)
	}
}

func TestConsumeJSONL(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"thread.started","thread_id":"thread-1"}`,
		`{"type":"item.completed","item":{"type":"agent_message","text":"done"}}`,
	}, "\n")
	result := &provider.Result{}
	var eventTypes []string
	if err := consumeJSONL(strings.NewReader(input), result, func(event provider.Event) error {
		eventTypes = append(eventTypes, event.Type)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if result.ProviderSessionID != "thread-1" || result.Output != "done" || len(eventTypes) != 2 {
		t.Fatalf("result=%+v events=%v", result, eventTypes)
	}
}
