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
// Licensed under the Apache License, Version 2.0.

package memory

import (
	"context"
	"encoding/json"
	"slices"
	"sort"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type orchestrationRepo struct{ s *Store }

func orchPolicyKey(tenant, namespace, agentRef string) string {
	return tenant + "\x00" + namespace + "\x00" + agentRef
}

func cloneDefinition(v *controlmodel.OrchestrationDefinition) *controlmodel.OrchestrationDefinition {
	c := *v
	c.DraftSpec = cloneJSON(v.DraftSpec)
	return &c
}
func cloneRevision(v *controlmodel.OrchestrationRevision) *controlmodel.OrchestrationRevision {
	c := *v
	c.Spec = cloneJSON(v.Spec)
	return &c
}
func cloneRun(v *controlmodel.OrchestrationRun) *controlmodel.OrchestrationRun {
	c := *v
	c.Input, c.Variables, c.Output = cloneJSON(v.Input), cloneJSON(v.Variables), cloneJSON(v.Output)
	c.PolicySnapshot, c.Usage = cloneJSON(v.PolicySnapshot), cloneJSON(v.Usage)
	return &c
}
func cloneRunNode(v *controlmodel.RunNode) *controlmodel.RunNode {
	c := *v
	c.Config, c.Input, c.Output = cloneJSON(v.Config), cloneJSON(v.Input), cloneJSON(v.Output)
	return &c
}
func cloneRunEdge(v *controlmodel.RunEdge) *controlmodel.RunEdge {
	c := *v
	c.OnStates = append([]controlmodel.RunNodeState(nil), v.OnStates...)
	c.Metadata = cloneJSON(v.Metadata)
	return &c
}
func cloneRunEvent(v *controlmodel.RunEvent) *controlmodel.RunEvent {
	c := *v
	c.Payload = cloneJSON(v.Payload)
	return &c
}
func cloneRuntimePolicy(v *controlmodel.AgentRuntimePolicy) *controlmodel.AgentRuntimePolicy {
	c := *v
	c.Candidates = append([]controlmodel.RuntimeBindingCandidate(nil), v.Candidates...)
	for i := range c.Candidates {
		c.Candidates[i].RequiredCapabilities = cloneJSON(v.Candidates[i].RequiredCapabilities)
		c.Candidates[i].SecurityConstraints = cloneJSON(v.Candidates[i].SecurityConstraints)
	}
	c.RetryPolicy = cloneJSON(v.RetryPolicy)
	return &c
}

func (r *orchestrationRepo) CreateDefinition(_ context.Context, in *controlmodel.OrchestrationDefinition) (*controlmodel.OrchestrationDefinition, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.definitions {
		if v.Tenant == in.Tenant && v.Namespace == in.Namespace && v.Name == in.Name && v.ArchivedAt == nil {
			return nil, store.ErrConflict
		}
	}
	now := time.Now().UTC()
	c := cloneDefinition(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	if c.DraftVersion == 0 {
		c.DraftVersion = 1
	}
	c.Version = 1
	c.CreatedAt, c.UpdatedAt = now, now
	r.s.definitions[c.ID] = c
	return cloneDefinition(c), nil
}
func (r *orchestrationRepo) GetDefinition(_ context.Context, id uuid.UUID) (*controlmodel.OrchestrationDefinition, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.definitions[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneDefinition(v), nil
}
func (r *orchestrationRepo) ListDefinitions(_ context.Context, f store.OrchestrationDefinitionFilter) ([]*controlmodel.OrchestrationDefinition, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.OrchestrationDefinition{}
	for _, v := range r.s.definitions {
		if (f.Tenant == "" || v.Tenant == f.Tenant) && (f.Namespace == "" || v.Namespace == f.Namespace) && (f.Name == "" || v.Name == f.Name) && (f.IncludeArchived || v.ArchivedAt == nil) {
			if slices.Contains(f.ExcludedIDs, v.ID) {
				continue
			}
			out = append(out, cloneDefinition(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt.After(out[j].UpdatedAt) })
	if f.Offset > 0 {
		if f.Offset >= len(out) {
			return []*controlmodel.OrchestrationDefinition{}, nil
		}
		out = out[f.Offset:]
	}
	if f.Limit > 0 && len(out) > f.Limit {
		out = out[:f.Limit]
	}
	return out, nil
}
func (r *orchestrationRepo) UpdateDefinition(_ context.Context, in *controlmodel.OrchestrationDefinition, expected int64) (*controlmodel.OrchestrationDefinition, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.definitions[in.ID]
	if v == nil {
		return nil, store.ErrNotFound
	}
	if v.Version != expected {
		return nil, store.ErrConflict
	}
	c := cloneDefinition(in)
	c.CreatedAt = v.CreatedAt
	c.CreatedBy = v.CreatedBy
	c.Version = v.Version + 1
	c.DraftVersion = v.DraftVersion + 1
	c.UpdatedAt = time.Now().UTC()
	r.s.definitions[c.ID] = c
	return cloneDefinition(c), nil
}
func (r *orchestrationRepo) CreateRevision(_ context.Context, in *controlmodel.OrchestrationRevision) (*controlmodel.OrchestrationRevision, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.definitions[in.DefinitionID] == nil {
		return nil, store.ErrNotFound
	}
	if in.ExpectedDefinitionVersion != 0 && r.s.definitions[in.DefinitionID].Version != in.ExpectedDefinitionVersion {
		return nil, store.ErrConflict
	}
	c := cloneRevision(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	var max int64
	for _, v := range r.s.revisions {
		if v.DefinitionID == c.DefinitionID && v.Revision > max {
			max = v.Revision
		}
	}
	if c.Revision == 0 {
		c.Revision = max + 1
	}
	if c.PublishedAt.IsZero() {
		c.PublishedAt = time.Now().UTC()
	}
	r.s.revisions[c.ID] = c
	return cloneRevision(c), nil
}
func (r *orchestrationRepo) GetRevision(_ context.Context, id uuid.UUID) (*controlmodel.OrchestrationRevision, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.revisions[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneRevision(v), nil
}
func (r *orchestrationRepo) ListRevisions(_ context.Context, definitionID uuid.UUID) ([]*controlmodel.OrchestrationRevision, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.OrchestrationRevision{}
	for _, v := range r.s.revisions {
		if v.DefinitionID == definitionID {
			out = append(out, cloneRevision(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Revision > out[j].Revision })
	return out, nil
}

func (r *orchestrationRepo) CreateRun(_ context.Context, in *controlmodel.OrchestrationRun) (*controlmodel.OrchestrationRun, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.runs {
		if in.IdempotencyKey != "" && v.Tenant == in.Tenant && v.Namespace == in.Namespace && v.IdempotencyKey == in.IdempotencyKey {
			return cloneRun(v), nil
		}
	}
	c := cloneRun(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	if c.State == "" {
		c.State = controlmodel.RunPlanned
	}
	now := time.Now().UTC()
	c.Version = 1
	c.CreatedAt, c.UpdatedAt = now, now
	if c.State == controlmodel.RunRunning {
		c.StartedAt = &now
	}
	r.s.runs[c.ID] = c
	return cloneRun(c), nil
}
func (r *orchestrationRepo) GetRun(_ context.Context, id uuid.UUID) (*controlmodel.OrchestrationRun, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.runs[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneRun(v), nil
}
func (r *orchestrationRepo) ListRuns(ctx context.Context, f store.OrchestrationRunFilter) ([]*controlmodel.OrchestrationRun, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.OrchestrationRun{}
	for _, v := range r.s.runs {
		if !r.s.canReadIssueLocked(ctx, v.RootIssueID) {
			continue
		}
		matchesIssue := f.IssueID == uuid.Nil || v.RootIssueID == f.IssueID
		if !matchesIssue {
			for _, task := range r.s.agentTasks {
				if task.OrchestrationRunID == v.ID && task.IssueID == f.IssueID {
					matchesIssue = true
					break
				}
			}
		}
		if (f.ParentNodeID == uuid.Nil || (v.ParentNodeID != nil && *v.ParentNodeID == f.ParentNodeID)) && (f.DefinitionID == uuid.Nil || (v.DefinitionRevisionID != nil && r.s.revisions[*v.DefinitionRevisionID] != nil && r.s.revisions[*v.DefinitionRevisionID].DefinitionID == f.DefinitionID)) && (f.Tenant == "" || v.Tenant == f.Tenant) && (f.Namespace == "" || v.Namespace == f.Namespace) && (f.RootIssueID == uuid.Nil || v.RootIssueID == f.RootIssueID) && matchesIssue && (f.State == "" || v.State == f.State) && (!f.ActiveOnly || !controlmodel.IsOrchestrationRunTerminal(v.State)) {
			out = append(out, cloneRun(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	if f.OldestFirst {
		sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	}
	if f.Offset > 0 {
		if f.Offset >= len(out) {
			return []*controlmodel.OrchestrationRun{}, nil
		}
		out = out[f.Offset:]
	}
	if f.Limit > 0 && len(out) > f.Limit {
		out = out[:f.Limit]
	}
	return out, nil
}
func (r *orchestrationRepo) TransitionRun(_ context.Context, id uuid.UUID, expected int64, to controlmodel.OrchestrationRunState, output json.RawMessage, code, message string) (*controlmodel.OrchestrationRun, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.runs[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	if v.Version != expected || !controlmodel.CanTransitionOrchestrationRun(v.State, to) {
		return nil, store.ErrConflict
	}
	v.State = to
	if output != nil {
		v.Output = cloneJSON(output)
	}
	v.WaitReason = ""
	if to == controlmodel.RunWaiting {
		v.WaitReason = code
		v.FailureCode, v.FailureMessage = "", ""
	} else {
		v.FailureCode, v.FailureMessage = code, message
	}
	now := time.Now().UTC()
	v.UpdatedAt = now
	v.Version++
	if to == controlmodel.RunRunning && v.StartedAt == nil {
		v.StartedAt = &now
	}
	if controlmodel.IsOrchestrationRunTerminal(to) {
		v.CompletedAt = &now

		key := "run-" + string(to) + ":" + id.String()
		found := false
		for _, event := range r.s.runEvents[id] {
			if event.IdempotencyKey == key {
				found = true
				break
			}
		}
		if !found {
			payload, _ := json.Marshal(map[string]any{"output": v.Output, "failureCode": v.FailureCode, "failureMessage": v.FailureMessage})
			r.s.runEvents[id] = append(r.s.runEvents[id], &controlmodel.RunEvent{ID: uuid.New(), RunID: id, Tenant: v.Tenant, Namespace: v.Namespace, Sequence: int64(len(r.s.runEvents[id]) + 1), Type: "run." + string(to), Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "orchestration"}, Payload: payload, IdempotencyKey: "run-" + string(to) + ":" + id.String(), OccurredAt: now})
			if signal := r.s.runEventSignals[id]; signal != nil {
				close(signal)
			}
			r.s.runEventSignals[id] = make(chan struct{})
		}
	}
	return cloneRun(v), nil
}
func (r *orchestrationRepo) CreateNode(_ context.Context, in *controlmodel.RunNode) (*controlmodel.RunNode, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if r.s.runs[in.RunID] == nil {
		return nil, store.ErrNotFound
	}
	for _, v := range r.s.runNodes {
		if v.RunID == in.RunID && v.NodeKey == in.NodeKey && v.Iteration == in.Iteration {
			return nil, store.ErrConflict
		}
	}
	c := cloneRunNode(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	if c.State == "" {
		c.State = controlmodel.RunNodePending
	}
	if c.Iteration == 0 {
		c.Iteration = 1
	}
	now := time.Now().UTC()
	c.Version = 1
	c.CreatedAt, c.UpdatedAt = now, now
	r.s.runNodes[c.ID] = c
	return cloneRunNode(c), nil
}
func (r *orchestrationRepo) GetNode(_ context.Context, id uuid.UUID) (*controlmodel.RunNode, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.runNodes[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneRunNode(v), nil
}
func (r *orchestrationRepo) ListNodes(_ context.Context, runID uuid.UUID) ([]*controlmodel.RunNode, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.RunNode{}
	for _, v := range r.s.runNodes {
		if v.RunID == runID {
			out = append(out, cloneRunNode(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].NodeKey < out[j].NodeKey })
	return out, nil
}
func (r *orchestrationRepo) TransitionNode(_ context.Context, id uuid.UUID, expected int64, to controlmodel.RunNodeState, output json.RawMessage, code, message string) (*controlmodel.RunNode, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	v := r.s.runNodes[id]
	if v == nil {
		return nil, store.ErrNotFound
	}
	if v.Version != expected || !controlmodel.CanTransitionRunNode(v.State, to) {
		return nil, store.ErrConflict
	}
	v.State = to
	if output != nil {
		v.Output = cloneJSON(output)
	}
	v.WaitReason = ""
	if to == controlmodel.RunNodeWaiting {
		v.WaitReason = code
		v.FailureCode, v.FailureMessage = "", ""
	} else {
		v.FailureCode, v.FailureMessage = code, message
	}
	now := time.Now().UTC()
	v.UpdatedAt = now
	v.Version++
	if (to == controlmodel.RunNodeRunning || to == controlmodel.RunNodeWaiting) && v.StartedAt == nil {
		v.StartedAt = &now
	}
	if controlmodel.IsRunNodeTerminal(to) {
		v.CompletedAt = &now
	}
	return cloneRunNode(v), nil
}
func (r *orchestrationRepo) CreateEdges(_ context.Context, in []*controlmodel.RunEdge) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, e := range in {
		if r.s.runs[e.RunID] == nil || r.s.runNodes[e.FromNodeID] == nil || r.s.runNodes[e.ToNodeID] == nil {
			return store.ErrNotFound
		}
		c := cloneRunEdge(e)
		if c.ID == uuid.Nil {
			c.ID = uuid.New()
		}
		if c.CreatedAt.IsZero() {
			c.CreatedAt = time.Now().UTC()
		}
		r.s.runEdges[c.ID] = c
	}
	return nil
}
func (r *orchestrationRepo) ListEdges(_ context.Context, runID uuid.UUID) ([]*controlmodel.RunEdge, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.RunEdge{}
	for _, v := range r.s.runEdges {
		if v.RunID == runID {
			out = append(out, cloneRunEdge(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Ordinal < out[j].Ordinal })
	return out, nil
}
func (r *orchestrationRepo) PutTeamSnapshot(_ context.Context, in *controlmodel.RunTeamSnapshot) (*controlmodel.RunTeamSnapshot, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	k := in.RunID.String() + "\x00" + in.TeamID.String()
	if v := r.s.runSnapshots[k]; v != nil {
		c := *v
		c.Snapshot = cloneJSON(v.Snapshot)
		return &c, nil
	}
	c := *in
	c.Snapshot = cloneJSON(in.Snapshot)
	if c.CreatedAt.IsZero() {
		c.CreatedAt = time.Now().UTC()
	}
	r.s.runSnapshots[k] = &c
	out := c
	return &out, nil
}
func (r *orchestrationRepo) ListTeamSnapshots(_ context.Context, runID uuid.UUID) ([]*controlmodel.RunTeamSnapshot, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.RunTeamSnapshot{}
	for _, v := range r.s.runSnapshots {
		if v.RunID == runID {
			c := *v
			c.Snapshot = cloneJSON(v.Snapshot)
			out = append(out, &c)
		}
	}
	return out, nil
}
func (r *orchestrationRepo) AppendRunEvent(_ context.Context, in *controlmodel.RunEvent) (*controlmodel.RunEvent, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for _, v := range r.s.runEvents[in.RunID] {
		if in.IdempotencyKey != "" && v.IdempotencyKey == in.IdempotencyKey {
			return cloneRunEvent(v), nil
		}
	}
	c := cloneRunEvent(in)
	if c.ID == uuid.Nil {
		c.ID = uuid.New()
	}
	c.Sequence = int64(len(r.s.runEvents[c.RunID]) + 1)
	if c.OccurredAt.IsZero() {
		c.OccurredAt = time.Now().UTC()
	}
	r.s.runEvents[c.RunID] = append(r.s.runEvents[c.RunID], c)
	if signal := r.s.runEventSignals[c.RunID]; signal != nil {
		close(signal)
	}
	r.s.runEventSignals[c.RunID] = make(chan struct{})
	return cloneRunEvent(c), nil
}
func (r *orchestrationRepo) ListRunEvents(_ context.Context, runID uuid.UUID, after int64, limit int) ([]*controlmodel.RunEvent, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.RunEvent{}
	for _, v := range r.s.runEvents[runID] {
		if v.Sequence > after {
			out = append(out, cloneRunEvent(v))
			if limit > 0 && len(out) >= limit {
				break
			}
		}
	}
	return out, nil
}
func (r *orchestrationRepo) WaitForRunEvent(ctx context.Context, runID uuid.UUID, after int64) error {
	r.s.mu.Lock()
	for _, event := range r.s.runEvents[runID] {
		if event.Sequence > after {
			r.s.mu.Unlock()
			return nil
		}
	}
	signal := r.s.runEventSignals[runID]
	if signal == nil {
		signal = make(chan struct{})
		r.s.runEventSignals[runID] = signal
	}
	r.s.mu.Unlock()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-signal:
		return nil
	}
}
func (r *orchestrationRepo) PutRuntimePolicy(_ context.Context, in *controlmodel.AgentRuntimePolicy) (*controlmodel.AgentRuntimePolicy, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	k := orchPolicyKey(in.Tenant, in.Namespace, in.AgentRef)
	c := cloneRuntimePolicy(in)
	now := time.Now().UTC()
	if old := r.s.runtimePolicies[k]; old != nil {
		if in.Version != 0 && in.Version != old.Version {
			return nil, store.ErrConflict
		}
		c.ID = old.ID
		c.CreatedAt = old.CreatedAt
		c.Version = old.Version + 1
	} else {
		if c.ID == uuid.Nil {
			c.ID = uuid.New()
		}
		c.CreatedAt = now
		c.Version = 1
	}
	if c.SelectionMode == "" {
		c.SelectionMode = "ordered"
	}
	if c.FallbackMode == "" {
		c.FallbackMode = "disabled"
	}
	c.UpdatedAt = now
	r.s.runtimePolicies[k] = c
	return cloneRuntimePolicy(c), nil
}
func (r *orchestrationRepo) GetRuntimePolicy(_ context.Context, tenant, namespace, agentRef string) (*controlmodel.AgentRuntimePolicy, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	v := r.s.runtimePolicies[orchPolicyKey(tenant, namespace, agentRef)]
	if v == nil {
		return nil, store.ErrNotFound
	}
	return cloneRuntimePolicy(v), nil
}
func (r *orchestrationRepo) ListRuntimePolicies(_ context.Context, tenant, namespace string, limit int) ([]*controlmodel.AgentRuntimePolicy, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	out := []*controlmodel.AgentRuntimePolicy{}
	for _, v := range r.s.runtimePolicies {
		if (tenant == "" || v.Tenant == tenant) && (namespace == "" || v.Namespace == namespace) {
			out = append(out, cloneRuntimePolicy(v))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].AgentRef < out[j].AgentRef })
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (r *orchestrationRepo) SetNodeInput(_ context.Context, id uuid.UUID, expected int64, input json.RawMessage) (*controlmodel.RunNode, error) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	node := r.s.runNodes[id]
	if node == nil {
		return nil, store.ErrNotFound
	}
	if node.Version != expected {
		return nil, store.ErrConflict
	}
	node.Input = cloneJSON(input)
	node.Version++
	return cloneRunNode(node), nil
}
