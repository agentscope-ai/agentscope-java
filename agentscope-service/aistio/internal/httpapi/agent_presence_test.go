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
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestParsePresence(t *testing.T) {
	p, ok := parsePresence("")
	if !ok || p != dataplane.PresenceLive {
		t.Fatalf("default: %q %v", p, ok)
	}
	if _, ok := parsePresence("nope"); ok {
		t.Fatal("expected invalid")
	}
}

func TestHistoricalAgentKeys(t *testing.T) {
	regKeys := map[string]struct{}{"default/live": {}}
	sessions := []*store.Session{
		{AgentName: "live", Namespace: "default"},
		{AgentName: "ghost", Namespace: "default"},
		{AgentName: "ghost", Namespace: "default"},
		{AgentName: "other", Namespace: "ns2"},
	}
	hist := historicalAgentKeys(sessions, regKeys)
	if len(hist) != 2 {
		t.Fatalf("hist=%v", hist)
	}
	if _, ok := hist["default/ghost"]; !ok {
		t.Fatalf("missing ghost: %v", hist)
	}
	if _, ok := hist["ns2/other"]; !ok {
		t.Fatalf("missing other: %v", hist)
	}
	if _, ok := hist["default/live"]; ok {
		t.Fatal("live should not be historical")
	}
}

func TestRegistryAgentBuckets(t *testing.T) {
	r := dataplane.NewRegistry()
	r.Upsert(dataplane.Entry{
		AgentName: "live", Namespace: "default", InstanceID: "1", BaseURL: "http://a",
	})
	r.Upsert(dataplane.Entry{
		AgentName: "off", Namespace: "default", InstanceID: "2", BaseURL: "http://b",
	})
	// Mark all stale then revive live. The cutoff must clear both entries'
	// registration instants, not just the first one's.
	r.MarkStale(time.Now().UTC().Add(2 * dataplane.StaleAfter))
	_ = r.Heartbeat("1")

	live, offline, keys, _ := registryAgentBuckets(r)
	if len(live) != 1 {
		t.Fatalf("live=%v", live)
	}
	if _, ok := live["default/live"]; !ok {
		t.Fatalf("live keys=%v", live)
	}
	if len(offline) != 1 {
		t.Fatalf("offline=%v", offline)
	}
	if _, ok := offline["default/off"]; !ok {
		t.Fatalf("offline keys=%v", offline)
	}
	if len(keys) != 2 {
		t.Fatalf("registryKeys=%v", keys)
	}
}
