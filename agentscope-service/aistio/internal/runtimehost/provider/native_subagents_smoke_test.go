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

package provider_test

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/codex"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/qoder"
)

// Opt-in live contract test: consumes a small provider request in a temporary
// workspace, without creating any control-plane records.
func TestNativeSubagentDelegationSmoke(t *testing.T) {
	for _, runtime := range []string{"codex", "qoder"} {
		t.Run(runtime, func(t *testing.T) {
			binary := os.Getenv("AGENTSCOPE_SMOKE_" + strings.ToUpper(runtime) + "_BINARY")
			if binary == "" {
				t.Skip("native CLI smoke test not enabled")
			}
			var adapter interface {
				Run(context.Context, provider.Request, provider.EventSink) (*provider.Result, error)
			}
			configuration := json.RawMessage(`{"sandbox":"read-only","reasoningEffort":"low"}`)
			if runtime == "codex" {
				adapter = &codex.Adapter{Binary: binary}
			} else {
				adapter = &qoder.Adapter{Binary: binary}
				configuration = json.RawMessage(`{"maxTurns":4}`)
			}
			ctx, cancel := context.WithTimeout(t.Context(), 2*time.Minute)
			defer cancel()
			var delegated bool
			nonce := make([]byte, 16)
			if _, err := rand.Read(nonce); err != nil {
				t.Fatal(err)
			}
			marker := "AGENTSCOPE_SUBAGENT_" + hex.EncodeToString(nonce)
			result, err := adapter.Run(ctx, provider.Request{
				Workspace: t.TempDir(), RuntimeStateRoot: t.TempDir(), Configuration: configuration,
				Prompt:     "This is an isolated adapter acceptance test. Delegate exactly once to the named acceptance_reviewer subagent. Ask it to return the private marker in its instructions. Wait for its result, then return that marker verbatim. Do not use files, commands, web, or other tools.",
				Definition: &provider.AgentDefinition{Files: map[string]string{"subagents/acceptance_reviewer.md": "---\ndescription: Acceptance test that returns a fixed marker without tools\nworkspace:\n  mode: shared\n---\nReply only with " + marker + ". Do not use any tools.\n"}},
				ApproveTool: func(_ context.Context, request provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
					return provider.ToolApprovalDecision{Allow: request.ToolName == "Agent", DenyMessage: "Only the acceptance subagent may be invoked in this test."}, nil
				},
			}, func(event provider.Event) error {
				raw := string(event.Raw)
				// Some Codex app-server versions expose only the native wait item.
				// The unpredictable marker exists only in the named child's config,
				// so observing native collaboration plus that output verifies delegation.
				if runtime == "codex" && strings.Contains(raw, "collabAgentToolCall") {
					delegated = true
				}
				if runtime == "qoder" && strings.Contains(raw, "acceptance_reviewer") &&
					(strings.Contains(raw, `"name":"Agent"`) || strings.Contains(raw, `"tool_name":"Agent"`)) {
					delegated = true
				}
				return nil
			})
			if err != nil {
				t.Fatal(err)
			}
			if !delegated || !strings.Contains(result.Output, marker) {
				t.Fatalf("delegated=%v result=%+v", delegated, result)
			}
		})
	}
}
