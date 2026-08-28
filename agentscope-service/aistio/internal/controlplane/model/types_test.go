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
