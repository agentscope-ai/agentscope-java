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

package model

import (
	"encoding/json"
	"testing"

	"github.com/google/uuid"
)

func TestHostedExecutionOverridesResolveProfileWithoutMutatingIt(t *testing.T) {
	base := json.RawMessage(`{"sandbox":"read-only","skipGitRepoCheck":false}`)
	overrides := &HostedExecutionOverrides{ReasoningEffort: "high", ServiceTier: "priority",
		ProviderConfiguration: json.RawMessage(`{"sandbox":"workspace-write"}`)}
	resolved, err := overrides.ResolveProviderConfiguration(base)
	if err != nil {
		t.Fatal(err)
	}
	var values map[string]any
	if err = json.Unmarshal(resolved, &values); err != nil {
		t.Fatal(err)
	}
	if values["sandbox"] != "workspace-write" || values["reasoningEffort"] != "high" ||
		values["serviceTier"] != "priority" || values["skipGitRepoCheck"] != false {
		t.Fatalf("resolved configuration=%v", values)
	}
	if string(base) != `{"sandbox":"read-only","skipGitRepoCheck":false}` {
		t.Fatalf("base configuration mutated: %s", base)
	}
}

func TestRuntimeBindingValidate(t *testing.T) {
	valid := []RuntimeBinding{
		{AgentID: uuid.New(), BindingID: uuid.New(), Kind: DataPlaneManaged, ManagedOwnerRef: "owner-1", ManagedDefinitionRef: "definition-1"},
		{AgentID: uuid.New(), BindingID: uuid.New(), Kind: DataPlaneExternalApplication, InstanceSelector: map[string]string{"app": "reviewer"}},
		{AgentID: uuid.New(), BindingID: uuid.New(), Kind: DataPlaneHostedRuntime, RuntimeProfileID: uuid.New(), RuntimePoolID: uuid.New()},
	}
	for _, binding := range valid {
		if err := binding.Validate(); err != nil {
			t.Fatalf("valid binding %+v rejected: %v", binding, err)
		}
	}
	if err := (RuntimeBinding{Kind: DataPlaneHostedRuntime}).Validate(); err == nil {
		t.Fatal("expected missing catalog and runtime IDs to fail")
	}
}

func TestExecutionAttemptTransitions(t *testing.T) {
	path := []ExecutionAttemptState{
		ExecutionQueued,
		ExecutionAssigned,
		ExecutionPreparing,
		ExecutionRunning,
		ExecutionSucceeded,
	}
	for i := 1; i < len(path); i++ {
		if !CanTransitionExecutionAttempt(path[i-1], path[i]) {
			t.Fatalf("expected transition %s -> %s", path[i-1], path[i])
		}
	}
	if CanTransitionExecutionAttempt(ExecutionSucceeded, ExecutionQueued) {
		t.Fatal("terminal execution must not be requeued")
	}
}

func TestRuntimeSecurityMatchesNestedLabelsAndBackend(t *testing.T) {
	labels := json.RawMessage(`{"region":"cn","trust":{"tier":"isolated","extra":true}}`)
	if !RuntimeSecurityMatches(DataPlaneHostedRuntime, labels,
		json.RawMessage(`{"backendKind":"hosted-runtime","trust":{"tier":"isolated"}}`)) {
		t.Fatal("nested security constraint should match")
	}
	if RuntimeSecurityMatches(DataPlaneHostedRuntime, labels, json.RawMessage(`{"region":"us"}`)) {
		t.Fatal("mismatched security constraint was accepted")
	}
	if RuntimeSecurityMatches(DataPlaneManaged, nil, json.RawMessage(`{"region":"cn"}`)) {
		t.Fatal("managed target without required labels was accepted")
	}
}

func TestRuntimeHostMatchesProfileAndPool(t *testing.T) {
	capabilities := json.RawMessage(`{"providers":{"codex":"0.152.1"},"sandbox":{"network":false}}`)
	profile := &RuntimeProfile{Provider: "codex", Requirements: json.RawMessage(`{"sandbox":{"network":false}}`)}
	if !RuntimeHostMatchesProfile(capabilities, profile) {
		t.Fatal("matching provider installation and requirements were rejected")
	}
	profile.Provider = "claude-code"
	if RuntimeHostMatchesProfile(capabilities, profile) {
		t.Fatal("Host without the requested provider was accepted")
	}
	pool := &RuntimePool{HostSelector: json.RawMessage(`{"region":"cn"}`)}
	if !RuntimeHostMatchesPool(json.RawMessage(`{"region":"cn","tier":"local"}`), pool) {
		t.Fatal("matching pool selector was rejected")
	}
	if RuntimeHostMatchesPool(json.RawMessage(`{"region":"us"}`), pool) {
		t.Fatal("mismatched pool selector was accepted")
	}
}
