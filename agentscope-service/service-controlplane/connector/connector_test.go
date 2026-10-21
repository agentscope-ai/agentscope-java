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
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

package connector

import "testing"

func TestBuildMetaCarriesTenantAndNamespace(t *testing.T) {
	connector := New(Config{AgentID: "agent-id", AgentKey: "agent-a", BindingID: "binding-id", InstanceKey: "instance-a", Generation: 1, Tenant: "tenant-a", Namespace: "namespace-a"})
	meta := connector.buildMeta()
	if meta.GetTenant() != "tenant-a" || meta.GetNamespace() != "namespace-a" {
		t.Fatalf("unexpected scope: tenant=%q namespace=%q", meta.GetTenant(), meta.GetNamespace())
	}
}

func TestBuildMetaDefaultsScope(t *testing.T) {
	connector := New(Config{AgentID: "agent-id", AgentKey: "agent-a", BindingID: "binding-id", InstanceKey: "instance-a", Generation: 1})
	meta := connector.buildMeta()
	if meta.GetTenant() != "default" || meta.GetNamespace() != "default" {
		t.Fatalf("unexpected default scope: tenant=%q namespace=%q", meta.GetTenant(), meta.GetNamespace())
	}
}
