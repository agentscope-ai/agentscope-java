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

//go:build !windows

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/spf13/cobra"
)

func TestLocalRuntimeLifecycle(t *testing.T) {
	root := t.TempDir()
	binary := filepath.Join(root, "aistio-runtime-host")
	script := "#!/bin/sh\ntrap 'exit 0' TERM INT\necho runtime-started\nprintf '{\"hostId\":\"test-host-id\",\"hostKey\":\"test-host-key\"}\\n' > \"$AISTIO_HOST_READY_FILE\"\nwhile true; do sleep 1; done\n"
	if err := os.WriteFile(binary, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	config := &localRuntimeConfig{
		Version: localRuntimeConfigVersion, ControlPlane: "http://127.0.0.1:1", Credential: "secret",
		Tenant: "default", Namespace: "default", Pool: "coding-default", Capacity: 1,
		WorkspaceRoot: filepath.Join(root, "workspaces"), StateRoot: filepath.Join(root, "state"),
		RuntimeHostBinary: binary, Providers: []localRuntimeProvider{{Name: "codex", Binary: "/bin/sh"}},
	}
	var output bytes.Buffer
	command := &cobra.Command{}
	command.SetOut(&output)
	command.SetErr(&output)
	if err := startLocalRuntime(command, config, false); err != nil {
		t.Fatal(err)
	}
	defer func() { _, _ = stopLocalRuntime(config, time.Second) }()
	pid, running := localRuntimeProcessState(config)
	if !running || pid <= 0 {
		t.Fatalf("process state pid=%d running=%v", pid, running)
	}
	var data []byte
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		data, _ = os.ReadFile(localRuntimeLogPath(config))
		if bytes.Contains(data, []byte("runtime-started")) {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	if !bytes.Contains(data, []byte("runtime-started")) {
		t.Fatalf("log = %q", data)
	}
	stopped, err := stopLocalRuntime(config, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !stopped {
		t.Fatal("expected running process to stop")
	}
	if _, running := localRuntimeProcessState(config); running {
		t.Fatal("process still reported as running")
	}
}

func TestLocalRuntimeStartWaitsForRegistrationAndReportsFailure(t *testing.T) {
	root := t.TempDir()
	binary := filepath.Join(root, "aistio-runtime-host")
	if err := os.WriteFile(binary, []byte("#!/bin/sh\necho registration-denied >&2\nexit 1\n"), 0o700); err != nil {
		t.Fatal(err)
	}
	config := &localRuntimeConfig{
		Version: localRuntimeConfigVersion, ControlPlane: "http://127.0.0.1:1", Credential: "bad-secret",
		Tenant: "default", Namespace: "default", Pool: "coding-default", Capacity: 1,
		WorkspaceRoot: filepath.Join(root, "workspaces"), StateRoot: filepath.Join(root, "state"),
		RuntimeHostBinary: binary, Providers: []localRuntimeProvider{{Name: "codex", Binary: "/bin/sh"}},
	}
	command := &cobra.Command{}
	var output bytes.Buffer
	command.SetOut(&output)
	command.SetErr(&output)
	err := startLocalRuntime(command, config, false)
	if err == nil || !strings.Contains(err.Error(), "registration-denied") {
		t.Fatalf("error = %v", err)
	}
	if _, running := localRuntimeProcessState(config); running {
		t.Fatal("failed daemon remained running")
	}
}

func TestRuntimeHostEnvironmentDoesNotLeakPlatformCredential(t *testing.T) {
	t.Setenv("AGENTSCOPE_API_TOKEN", "platform-secret")
	t.Setenv("AISTIO_INTERNAL_TOKEN", "stale-runtime-secret")
	config := &localRuntimeConfig{
		ControlPlane: "https://agentscope.example", Credential: "scoped-host-secret",
		Tenant: "acme", Namespace: "engineering", Pool: "coding", Capacity: 1,
		WorkspaceRoot: "/work", StateRoot: "/state",
		Providers: []localRuntimeProvider{
			{Name: "codex", Binary: "/bin/codex"},
			{Name: "qwenpaw", Binary: "/bin/qwenpaw"},
			{Name: "openclaw", Binary: "/bin/openclaw"},
		},
	}
	environment := runtimeHostEnvironment(config)
	values := map[string][]string{}
	for _, item := range environment {
		key, value, _ := strings.Cut(item, "=")
		values[key] = append(values[key], value)
	}
	if len(values["AGENTSCOPE_API_TOKEN"]) != 0 {
		t.Fatal("platform credential leaked to daemon environment")
	}
	if got := values["AISTIO_INTERNAL_TOKEN"]; len(got) != 1 || got[0] != "scoped-host-secret" {
		t.Fatalf("runtime credential values = %v", got)
	}
	for key, want := range map[string]string{
		"AISTIO_CODEX_BINARY":    "/bin/codex",
		"AISTIO_QWENPAW_BINARY":  "/bin/qwenpaw",
		"AISTIO_OPENCLAW_BINARY": "/bin/openclaw",
	} {
		if got := values[key]; len(got) != 1 || got[0] != want {
			t.Fatalf("%s values = %v, want %q", key, got, want)
		}
	}
}
