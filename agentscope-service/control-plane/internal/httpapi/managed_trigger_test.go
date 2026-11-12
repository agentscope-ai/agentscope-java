// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/product"
	"testing"
)

func TestManagedTriggerSessionIdentitySeparatesConversationAndTurn(t *testing.T) {
	a := &model.Agent{ID: uuid.New(), Tenant: "t", Namespace: "n"}
	r := product.ManagedTrigger{OwnerID: "owner", ActorID: "human", ExternalKey: "channel:conversation", RequestID: "first"}
	first := managedTriggerSessionID(a, r)
	r.RequestID = "second"
	if managedTriggerSessionID(a, r) != first {
		t.Fatal("one conversation received two Sessions")
	}
	for _, change := range []func(*product.ManagedTrigger){func(r *product.ManagedTrigger) { r.OwnerID = "other" }, func(r *product.ManagedTrigger) { r.ActorID = "other" }, func(r *product.ManagedTrigger) { r.ExternalKey = "other" }, func(r *product.ManagedTrigger) { r.EnvironmentID = "other" }} {
		changed := r
		change(&changed)
		if managedTriggerSessionID(a, changed) == first {
			t.Fatal("scope identity collided")
		}
	}
	r.ExternalKey = ""
	r.RequestID = "fire-one"
	one := managedTriggerSessionID(a, r)
	r.RequestID = "fire-two"
	if managedTriggerSessionID(a, r) == one {
		t.Fatal("independent fires shared a Session")
	}
	r.RequestID = "fire-one"
	if managedTriggerSessionID(a, r) != one {
		t.Fatal("fire retry changed Session identity")
	}
}
