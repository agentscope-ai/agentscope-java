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

package httpapi

import (
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestEnrichSessionInstanceUsesHealthyPeerAfterAffinityMiss(t *testing.T) {
	registry := dataplane.NewRegistry()
	registry.Upsert(dataplane.Entry{
		Tenant: "tenant-a", Namespace: "ns-a", AgentName: "external-agent",
		InstanceID: "current-instance", BaseURL: "http://127.0.0.1:8089",
		Capabilities: []string{"message-query", "context-query"}, ContractLevel: 2,
	})
	s := &Server{registry: registry}
	sess := &store.Session{
		Tenant: "tenant-a", Namespace: "ns-a", AgentName: "external-agent",
		InstanceRef: "stale-instance",
	}
	item := SessionWithSnapshot{Session: sess}

	s.enrichSessionInstance(sess, &item)

	if item.InstanceHealthy == nil || !*item.InstanceHealthy {
		t.Fatalf("healthy fallback peer reported unhealthy: %+v", item)
	}
	if item.InstanceBaseURL != "http://127.0.0.1:8089" || item.ContractLevel != 2 ||
		len(item.Capabilities) != 2 {
		t.Fatalf("fallback metadata missing: %+v", item)
	}
}
