// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"encoding/json"
	"reflect"
	"testing"

	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
)

func TestManagedAuthorizationUsesFrozenDefinitionAndSelectedMounts(t *testing.T) {
	contract := &serviceapi.Contract{EnvironmentID: "chosen-env", Definitions: map[string]json.RawMessage{"agent": json.RawMessage(`{"workspaceId":"frozen-workspace","defaultVaultIds":["old-vault"],"defaultMemoryStoreIds":["frozen-memory"],"mcpServers":[{"oauthVaultId":"old-oauth"}]}`)}, SessionOptions: json.RawMessage(`{"vaultIds":["chosen-vault"],"agentOverrides":{"mcpServers":[{"oauthVaultId":"chosen-oauth"}]}}`)}
	dependencies, err := managedFrozenDependencies(contract, "agent")
	want := []string{"environment:chosen-env", "memory:frozen-memory", "vault:chosen-oauth", "vault:chosen-vault", "workspace:frozen-workspace"}
	if err != nil || !reflect.DeepEqual(dependencies, want) {
		t.Fatalf("frozen graph: %v %v", dependencies, err)
	}
	contract.SessionOptions = json.RawMessage(`{"vaultIds":[],"memoryStoreIds":[],"agentOverrides":{"mcpServers":[]}}`)
	dependencies, err = managedFrozenDependencies(contract, "agent")
	if err != nil || !reflect.DeepEqual(dependencies, []string{"environment:chosen-env", "workspace:frozen-workspace"}) {
		t.Fatalf("empty overrides did not remove defaults: %v %v", dependencies, err)
	}
}
