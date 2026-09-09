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
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/pelletier/go-toml/v2"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

func TestChildTurnCannotFinishOrReplaceParentResult(t *testing.T) {
	input := strings.Join([]string{
		`{"method":"thread/started","params":{"thread":{"id":"child"}}}`,
		`{"method":"turn/completed","params":{"threadId":"child","turn":{"status":"failed","error":{"message":"child failed"},"items":[{"type":"agentMessage","text":"child output"}]}}}`,
		`{"method":"turn/completed","params":{"threadId":"parent","turn":{"status":"completed","items":[{"type":"agentMessage","text":"parent reviewed child result"}]}}}`,
	}, "\n")
	var childEvents int
	client := newAppServerClient(context.Background(), io.Discard, strings.NewReader(input), func(event provider.Event) error {
		if event.ProviderSessionID == "child" {
			childEvents++
		}
		return nil
	}, nil)
	client.rootThreadID, client.result.ProviderSessionID = "parent", "parent"
	if err := client.waitForTurn(); err != nil {
		t.Fatal(err)
	}
	if client.result.ProviderSessionID != "parent" || client.result.Output != "parent reviewed child result" || childEvents != 2 {
		t.Fatalf("result=%+v childEvents=%d", client.result, childEvents)
	}
}

func TestSubagentProjectionPreservesConfigAndCleansOwnedFiles(t *testing.T) {
	workspace := t.TempDir()
	item := provider.NativeSubagent{Name: "reviewer", Description: "Review API", Prompt: "Preserve \"quotes\".\nReview files.", Model: "test-model"}
	cleanup, err := projectSubagents(workspace, []provider.NativeSubagent{item})
	if err != nil {
		t.Fatal(err)
	}
	filename := filepath.Join(workspace, ".agentscope", "native", "codex", "agents", "reviewer.toml")
	data, err := os.ReadFile(filename)
	if err != nil {
		t.Fatal(err)
	}
	var parsed map[string]string
	if err := toml.Unmarshal(data, &parsed); err != nil {
		t.Fatal(err)
	}
	if parsed["name"] != item.Name || parsed["developer_instructions"] != item.Prompt || parsed["model"] != item.Model {
		t.Fatalf("config=%+v", parsed)
	}
	// An interrupted run is replaced safely on resume; the old cleanup cannot
	// remove the new definition when the content changed.
	item.Prompt = "Updated review instructions"
	cleanupNext, err := projectSubagents(workspace, []provider.NativeSubagent{item})
	if err != nil {
		t.Fatal(err)
	}
	cleanup()
	if _, err := os.Stat(filename); err != nil {
		t.Fatalf("old cleanup removed new projection: %v", err)
	}
	cleanupNext()
	if _, err := os.Stat(filename); !os.IsNotExist(err) {
		t.Fatalf("projection not cleaned: %v", err)
	}
}

func TestSubagentProjectionDoesNotOverwriteRepositoryOrFollowSymlink(t *testing.T) {
	for _, symlink := range []bool{false, true} {
		t.Run(map[bool]string{false: "repository file", true: "symlink"}[symlink], func(t *testing.T) {
			workspace, outside := t.TempDir(), t.TempDir()
			root := filepath.Join(workspace, ".agentscope", "native", "codex", "agents")
			if symlink {
				if err := os.Symlink(outside, filepath.Join(workspace, ".agentscope")); err != nil {
					t.Fatal(err)
				}
			} else {
				if err := os.MkdirAll(root, 0o750); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(filepath.Join(root, "reviewer.toml"), []byte("# repository content"), 0o640); err != nil {
					t.Fatal(err)
				}
			}
			if cleanup, err := projectSubagents(workspace, []provider.NativeSubagent{{Name: "reviewer", Description: "review", Prompt: "review"}}); err == nil {
				cleanup()
				t.Fatal("overwrote repository-owned target")
			}
			if !symlink {
				data, _ := os.ReadFile(filepath.Join(root, "reviewer.toml"))
				if string(data) != "# repository content" {
					t.Fatalf("repository content changed: %q", data)
				}
			}
		})
	}
}
