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
	"context"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func agentKey(namespace, name string) string {
	ns := namespace
	if ns == "" {
		ns = defaultNamespace
	}
	return ns + "/" + name
}

// registryAgentBuckets splits AggregateAgents into live vs offline keys.
func registryAgentBuckets(reg *dataplane.Registry) (live, offline, registryKeys map[string]struct{}, summaries []dataplane.AgentSummary) {
	live = map[string]struct{}{}
	offline = map[string]struct{}{}
	registryKeys = map[string]struct{}{}
	if reg == nil {
		return live, offline, registryKeys, nil
	}
	summaries = reg.AggregateAgents()
	for _, a := range summaries {
		key := agentKey(a.Namespace, a.Name)
		registryKeys[key] = struct{}{}
		switch dataplane.ClassifyPresence(a.HealthyCount, a.InstanceCount) {
		case dataplane.PresenceLive:
			live[key] = struct{}{}
		case dataplane.PresenceOffline:
			offline[key] = struct{}{}
		}
	}
	return live, offline, registryKeys, summaries
}

// historicalAgentKeys returns ns/name from sessions with no registry entry.
func historicalAgentKeys(sessions []*store.Session, registryKeys map[string]struct{}) map[string]struct{} {
	out := map[string]struct{}{}
	for _, sess := range sessions {
		if sess == nil || sess.AgentName == "" {
			continue
		}
		key := agentKey(sess.Namespace, sess.AgentName)
		if _, inReg := registryKeys[key]; inReg {
			continue
		}
		out[key] = struct{}{}
	}
	return out
}

func listSessionsForPresence(ctx context.Context, st store.Store) []*store.Session {
	if st == nil {
		return nil
	}
	sessions, err := st.Sessions().List(ctx, store.SessionFilter{Limit: 5000})
	if err != nil || sessions == nil {
		return nil
	}
	return sessions
}

func parsePresence(raw string) (string, bool) {
	p := strings.ToLower(strings.TrimSpace(raw))
	if p == "" {
		p = dataplane.PresenceLive
	}
	switch p {
	case dataplane.PresenceLive, dataplane.PresenceOffline, dataplane.PresenceHistorical, dataplane.PresenceAll:
		return p, true
	default:
		return "", false
	}
}

func splitAgentKey(key string) (namespace, name string) {
	ns, name, ok := strings.Cut(key, "/")
	if !ok {
		return defaultNamespace, key
	}
	return ns, name
}
