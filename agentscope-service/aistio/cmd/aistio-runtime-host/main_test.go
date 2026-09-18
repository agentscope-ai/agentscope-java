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

package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestConfiguredProviders(t *testing.T) {
	providers, err := configuredProviders("codex,claude-code,qoder,qwenpaw,openclaw",
		"/bin/codex", "/bin/claude", "/bin/qodercli", "/bin/qwenpaw", "/bin/openclaw")
	if err != nil {
		t.Fatal(err)
	}
	if providers["codex"] == nil || providers["claude-code"] == nil || providers["qoder"] == nil ||
		providers["qwenpaw"] == nil || providers["openclaw"] == nil || len(providers) != 5 {
		t.Fatalf("providers=%v", providers)
	}
	if _, err := configuredProviders("unknown", "codex", "claude", "qodercli", "qwenpaw", "openclaw"); err == nil {
		t.Fatal("expected unsupported provider error")
	}
	automatic, err := configuredProviders("auto", "/bin/sh", "/bin/sh", "/bin/sh", "/bin/sh", "/bin/sh")
	if err != nil || len(automatic) != 5 {
		t.Fatalf("automatic providers=%v err=%v", automatic, err)
	}
}

func TestResolveAgentScopeBinaryAcceptsExplicitExecutable(t *testing.T) {
	path := filepath.Join(t.TempDir(), "agentscope")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"), 0o700); err != nil {
		t.Fatal(err)
	}
	got := resolveAgentScopeBinary(path)
	if got != path {
		t.Fatalf("resolved=%q want=%q", got, path)
	}
}
