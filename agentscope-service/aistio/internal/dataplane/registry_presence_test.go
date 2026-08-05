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

package dataplane

import (
	"testing"
	"time"
)

func TestClassifyPresence(t *testing.T) {
	cases := []struct {
		healthy, instances int
		want               string
	}{
		{1, 1, PresenceLive},
		{2, 3, PresenceLive},
		{0, 2, PresenceOffline},
		{0, 0, PresenceHistorical},
	}
	for _, tc := range cases {
		if got := ClassifyPresence(tc.healthy, tc.instances); got != tc.want {
			t.Fatalf("ClassifyPresence(%d,%d)=%q want %q", tc.healthy, tc.instances, got, tc.want)
		}
	}
}

func TestFilterAgentsByPresence(t *testing.T) {
	in := []AgentSummary{
		{Name: "live-a", Namespace: "default", HealthyCount: 1, InstanceCount: 1},
		{Name: "offline-b", Namespace: "default", HealthyCount: 0, InstanceCount: 2},
	}
	live := FilterAgentsByPresence(in, PresenceLive)
	if len(live) != 1 || live[0].Name != "live-a" {
		t.Fatalf("live filter: %+v", live)
	}
	offline := FilterAgentsByPresence(in, PresenceOffline)
	if len(offline) != 1 || offline[0].Name != "offline-b" {
		t.Fatalf("offline filter: %+v", offline)
	}
	all := FilterAgentsByPresence(in, PresenceAll)
	if len(all) != 2 {
		t.Fatalf("all filter: %+v", all)
	}
}

func TestAggregateAgentsHealthyCounts(t *testing.T) {
	r := NewRegistry()
	r.Upsert(Entry{AgentName: "a", Namespace: "default", InstanceID: "i1", BaseURL: "http://x"})
	r.Upsert(Entry{AgentName: "b", Namespace: "default", InstanceID: "i2", BaseURL: "http://y"})
	// Force b stale.
	r.MarkStale(time.Now().UTC().Add(StaleAfter + time.Second))
	// Re-health a via heartbeat (MarkStale only flips previously healthy).
	// MarkStale marked both; heartbeat a back.
	if !r.Heartbeat("i1") {
		t.Fatal("heartbeat a")
	}

	sums := r.AggregateAgents()
	byName := map[string]AgentSummary{}
	for _, s := range sums {
		byName[s.Name] = s
	}
	if byName["a"].HealthyCount != 1 {
		t.Fatalf("a healthy: %+v", byName["a"])
	}
	if byName["b"].HealthyCount != 0 || byName["b"].InstanceCount != 1 {
		t.Fatalf("b offline: %+v", byName["b"])
	}
	live := FilterAgentsByPresence(sums, PresenceLive)
	if len(live) != 1 || live[0].Name != "a" {
		t.Fatalf("live after stale: %+v", live)
	}
}
