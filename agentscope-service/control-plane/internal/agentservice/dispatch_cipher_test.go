// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package agentservice

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
)

func TestDispatchConfigurationIsSealedAndBoundToTaskAndFrozenIdentity(t *testing.T) {
	cipher, err := NewCipher(strings.Repeat("runtime-test-key", 3))
	if err != nil {
		t.Fatal(err)
	}
	value := model.RuntimeDispatchSnapshot{AgentServiceCommandID: "portal-turn", SessionID: "session", ResolvedAt: time.Now().UTC(),
		Binding: model.RuntimeBinding{AgentID: uuid.New(), BindingID: uuid.New(), Kind: model.DataPlaneHostedRuntime,
			ExecutionOverrides: &model.HostedExecutionOverrides{CustomArgs: []string{"private-fixture-key"}}},
		RuntimeProfile:                &model.RuntimeProfile{ID: uuid.New(), Provider: "codex", Version: 2, Configuration: json.RawMessage(`{"apiKey":"private-fixture-key"}`)},
		RuntimePool:                   &model.RuntimePool{ID: uuid.New(), Version: 3},
		Definition:                    json.RawMessage(`{"files":{"AGENTS.md":"private-fixture-key"}}`),
		ResolvedProviderConfiguration: json.RawMessage(`{"apiKey":"private-fixture-key","limit":9007199254740993}`)}
	protected, err := cipher.ProtectDispatch("task", value)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(protected)
	if strings.Contains(string(raw), "private-fixture-key") || len(protected.Definition) != 0 || len(protected.RuntimeProfile.Configuration) != 0 || protected.Binding.ExecutionOverrides != nil {
		t.Fatal("execution configuration remained in scheduling metadata")
	}
	if len(value.RuntimeProfile.Configuration) == 0 || value.Binding.ExecutionOverrides == nil {
		t.Fatal("sealing modified the source registry value")
	}
	var reloaded model.RuntimeDispatchSnapshot
	if err = json.Unmarshal(raw, &reloaded); err != nil {
		t.Fatal(err)
	}
	original, err := cipher.RevealDispatch("task", reloaded)
	if err != nil || string(original.ResolvedProviderConfiguration) != string(value.ResolvedProviderConfiguration) || string(original.Definition) != string(value.Definition) {
		t.Fatal("frozen configuration or exact numbers changed during reload")
	}
	if _, err = cipher.RevealDispatch("another-task", reloaded); err == nil {
		t.Fatal("ciphertext was copied across tasks")
	}
	reloaded.RuntimeProfile.Provider = "another-provider"
	if _, err = cipher.RevealDispatch("task", reloaded); err == nil {
		t.Fatal("provider identity was substituted")
	}
	if _, err = cipher.RevealDispatch("task", value); err == nil {
		t.Fatal("plaintext Agent Service dispatch was accepted")
	}
	var missing *Cipher
	if _, err = missing.ProtectDispatch("task", value); err == nil {
		t.Fatal("configuration was persisted without its key")
	}
}

func TestDispatchReloadToleratesJSONBFormattingOfQueryCapabilities(t *testing.T) {
	cipher, _ := NewCipher(strings.Repeat("runtime-test-key", 3))
	value := model.RuntimeDispatchSnapshot{Binding: model.RuntimeBinding{AgentID: uuid.New(), Kind: model.DataPlaneHostedRuntime},
		RuntimeProfile: &model.RuntimeProfile{ID: uuid.New(), Provider: "codex", Version: 1, Configuration: json.RawMessage(`{"model":"frozen"}`), Requirements: json.RawMessage(`{"z":1e2,"a":1}`)},
		Capabilities:   json.RawMessage(`{"z":1e2,"a":1}`)}
	protected, err := cipher.ProtectDispatch("task", value)
	if err != nil {
		t.Fatal(err)
	}
	protected.Capabilities = json.RawMessage(`{"a": 1, "z": 100}`)
	protected.RuntimeProfile.Requirements = json.RawMessage(`{"a": 1, "z": 100}`)
	restored, err := cipher.RevealDispatch("task", protected)
	if err != nil || string(restored.RuntimeProfile.Configuration) != `{"model":"frozen"}` {
		t.Fatal("JSONB formatting changed the ciphertext identity")
	}
}

// This digest uses the persisted identity schema from before component renaming.
func TestDispatchIdentityRetainsPersistedNames(t *testing.T) {
	value := model.RuntimeDispatchSnapshot{
		AgentServiceCommandID: "command", SessionID: "session", SnapshotCipherVersion: 1,
		ResolvedAt: time.Date(2026, 10, 19, 0, 0, 0, 0, time.UTC),
		Binding:    model.RuntimeBinding{Kind: model.DataPlaneHostedRuntime},
	}
	identity, err := dispatchIdentity("task", value)
	if err != nil || identity != "dispatch:task:a4e666b5643f0efe29459b2b754a5d32c8ed9a00fbf9a5ce5ecb4814f90077f7" {
		t.Fatalf("persisted encryption identity changed: %s (%v)", identity, err)
	}
	raw, err := json.Marshal(value)
	if err != nil || !strings.Contains(string(raw), `"portalCommandId":"command"`) {
		t.Fatalf("persisted snapshot key changed: %s (%v)", raw, err)
	}
	var reloaded model.RuntimeDispatchSnapshot
	if err := json.Unmarshal([]byte(`{"portalCommandId":"command"}`), &reloaded); err != nil || reloaded.AgentServiceCommandID != "command" {
		t.Fatalf("old snapshot cannot be read: %#v (%v)", reloaded, err)
	}
}
