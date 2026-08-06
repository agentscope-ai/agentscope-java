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
	"strings"
	"sync"
	"time"
)

const (
	SourceSelfRegister = "self-register"
	SourceKubernetes   = "kubernetes"
	DefaultHeartbeat   = 15 * time.Second
	StaleAfter         = 45 * time.Second
)

// Entry is one registered data-plane instance.
type Entry struct {
	AgentName     string                 `json:"agentName"`
	Namespace     string                 `json:"namespace"`
	InstanceID    string                 `json:"instanceId"`
	BaseURL       string                 `json:"baseUrl"`
	Runtime       string                 `json:"runtime,omitempty"`
	Framework     string                 `json:"framework,omitempty"`
	ContractLevel int32                  `json:"contractLevel"`
	Capabilities  []string               `json:"capabilities,omitempty"`
	AgentConfig   map[string]interface{} `json:"agentConfig,omitempty"`
	Healthy       bool                   `json:"healthy"`
	LastSeenAt    time.Time              `json:"lastSeenAt"`
	Source        string                 `json:"source"`
	RegisteredAt  time.Time              `json:"registeredAt"`
}

// Registry is an in-process data-plane instance registry used when
// Kubernetes discovery is unavailable (and alongside it when both run).
type Registry struct {
	mu   sync.RWMutex
	byID map[string]*Entry
}

// NewRegistry returns an empty registry.
func NewRegistry() *Registry {
	return &Registry{byID: make(map[string]*Entry)}
}

// Upsert registers or refreshes a data-plane instance. Returns the heartbeat interval.
func (r *Registry) Upsert(e Entry) time.Duration {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now().UTC()
	if e.Namespace == "" {
		e.Namespace = "default"
	}
	if e.Source == "" {
		e.Source = SourceSelfRegister
	}
	e.Healthy = true
	e.LastSeenAt = now
	if prev, ok := r.byID[e.InstanceID]; ok {
		e.RegisteredAt = prev.RegisteredAt
	} else {
		e.RegisteredAt = now
	}
	cp := e
	cp.Capabilities = append([]string(nil), e.Capabilities...)
	r.byID[e.InstanceID] = &cp
	return DefaultHeartbeat
}

// Heartbeat refreshes last_seen for an instance. Returns false if unknown.
func (r *Registry) Heartbeat(instanceID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.byID[instanceID]
	if !ok {
		return false
	}
	e.LastSeenAt = time.Now().UTC()
	e.Healthy = true
	return true
}

// Delete removes an instance.
func (r *Registry) Delete(instanceID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.byID[instanceID]; !ok {
		return false
	}
	delete(r.byID, instanceID)
	return true
}

// Get returns a copy of the entry, or nil.
func (r *Registry) Get(instanceID string) *Entry {
	r.mu.RLock()
	defer r.mu.RUnlock()
	e, ok := r.byID[instanceID]
	if !ok {
		return nil
	}
	return clone(e)
}

// List returns all entries.
func (r *Registry) List() []*Entry {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]*Entry, 0, len(r.byID))
	for _, e := range r.byID {
		out = append(out, clone(e))
	}
	return out
}

// ListByAgent returns entries for an agent name/namespace.
func (r *Registry) ListByAgent(agentName, namespace string) []*Entry {
	if namespace == "" {
		namespace = "default"
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	var out []*Entry
	for _, e := range r.byID {
		if e.AgentName == agentName && e.Namespace == namespace {
			out = append(out, clone(e))
		}
	}
	return out
}

// MarkStale marks entries whose last_seen is older than StaleAfter as unhealthy.
// Returns the instance IDs that transitioned to unhealthy.
func (r *Registry) MarkStale(now time.Time) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var stale []string
	for id, e := range r.byID {
		if e.Healthy && now.Sub(e.LastSeenAt) > StaleAfter {
			e.Healthy = false
			stale = append(stale, id)
		}
	}
	return stale
}

// AgentSummaries aggregates registry entries into one row per agent.
type AgentSummary struct {
	Name           string   `json:"name"`
	Namespace      string   `json:"namespace"`
	Runtime        string   `json:"runtime,omitempty"`
	Framework      string   `json:"framework,omitempty"`
	ContractLevel  int32    `json:"contractLevel"`
	Capabilities   []string `json:"capabilities,omitempty"`
	Replicas       string   `json:"replicas"` // "ready/desired"
	HealthyCount   int      `json:"healthyCount"`
	InstanceCount  int      `json:"instanceCount"`
	Instances      []string `json:"instances,omitempty"`
}

// Presence classification for Operate fleet views.
const (
	PresenceLive       = "live"
	PresenceOffline    = "offline"
	PresenceHistorical = "historical"
	PresenceAll        = "all"
)

// ClassifyPresence maps healthy/instance counts to a presence bucket.
func ClassifyPresence(healthyCount, instanceCount int) string {
	if healthyCount > 0 {
		return PresenceLive
	}
	if instanceCount > 0 {
		return PresenceOffline
	}
	return PresenceHistorical
}

// FilterAgentsByPresence keeps summaries matching presence (live|offline|all).
// historical is not derived from registry summaries.
func FilterAgentsByPresence(summaries []AgentSummary, presence string) []AgentSummary {
	presence = strings.ToLower(strings.TrimSpace(presence))
	if presence == "" || presence == PresenceAll {
		return summaries
	}
	out := make([]AgentSummary, 0, len(summaries))
	for _, s := range summaries {
		p := ClassifyPresence(s.HealthyCount, s.InstanceCount)
		switch presence {
		case PresenceLive:
			if p == PresenceLive {
				out = append(out, s)
			}
		case PresenceOffline:
			if p == PresenceOffline {
				out = append(out, s)
			}
		default:
			out = append(out, s)
		}
	}
	return out
}

// AggregateAgents returns one summary per agentName/namespace.
func (r *Registry) AggregateAgents() []AgentSummary {
	r.mu.RLock()
	defer r.mu.RUnlock()
	type key struct{ ns, name string }
	m := map[key]*AgentSummary{}
	for _, e := range r.byID {
		k := key{e.Namespace, e.AgentName}
		s, ok := m[k]
		if !ok {
			s = &AgentSummary{
				Name:          e.AgentName,
				Namespace:     e.Namespace,
				Runtime:       e.Runtime,
				Framework:     e.Framework,
				ContractLevel: e.ContractLevel,
				Capabilities:  append([]string(nil), e.Capabilities...),
			}
			m[k] = s
		}
		s.InstanceCount++
		s.Instances = append(s.Instances, e.InstanceID)
		if e.Healthy {
			s.HealthyCount++
		}
		if e.ContractLevel > s.ContractLevel {
			s.ContractLevel = e.ContractLevel
		}
	}
	out := make([]AgentSummary, 0, len(m))
	for _, s := range m {
		s.Replicas = formatReplicas(s.HealthyCount, s.InstanceCount)
		out = append(out, *s)
	}
	return out
}

func formatReplicas(ready, desired int) string {
	return itoa(ready) + "/" + itoa(desired)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

func clone(e *Entry) *Entry {
	cp := *e
	cp.Capabilities = append([]string(nil), e.Capabilities...)
	if e.AgentConfig != nil {
		cp.AgentConfig = make(map[string]interface{}, len(e.AgentConfig))
		for k, v := range e.AgentConfig {
			cp.AgentConfig[k] = v
		}
	}
	return &cp
}
