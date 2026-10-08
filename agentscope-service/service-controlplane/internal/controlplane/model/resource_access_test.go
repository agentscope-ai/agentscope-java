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

import "testing"

func TestResourceRolesAndDependencyGrants(t *testing.T) {
	n := Namespace{Tenant: "test", Name: "engineering", DisplayName: "Engineering", Kind: "shared", Owner: "alice", Members: map[string][]string{"bob": {"member"}}, Groups: map[string]AccessGroup{"builders": {Name: "Builders", Members: []string{"carol"}, Roles: []string{"developer"}}}, Resources: map[string]ResourcePolicy{"agent:worker": {Mode: "restricted", Groups: map[string][]string{"builders": {"use"}}, Users: map[string][]string{"bob": {"discover"}}}, "vault:secret": {Mode: "restricted", Consumers: []string{"agent:worker"}}}}
	if e := n.Validate(); e != nil {
		t.Fatal(e)
	}
	if !n.Decide("carol", "agent:worker", "use").Allowed || n.Decide("carol", "agent:worker", "edit").Allowed {
		t.Fatal("group grant lost or widened")
	}
	if n.Decide("bob", "agent:worker", "use").Allowed || !n.Decide("bob", "agent:worker", "discover").Allowed {
		t.Fatal("restricted resource ignored")
	}
	if n.Decide("carol", "vault:secret", "inspect").Allowed || n.Decide("carol", "vault:secret", "use").Allowed {
		t.Fatal("consumer permission escaped to user")
	}
	if !n.Decide("alice", "agent:worker", "manage").Allowed {
		t.Fatal("owner cannot recover resource permissions")
	}
	if n.Decide("outsider", "agent:worker", "use").Allowed {
		t.Fatal("foreign user admitted")
	}
	delete(n.Groups, "builders")
	if n.Decide("carol", "agent:worker", "use").Allowed {
		t.Fatal("removed group access survived")
	}
}
