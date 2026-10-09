/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"context"
	"fmt"
	"sort"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

// Traverse only explicit parent links; namespace-wide runs never establish ownership.
func (s *Server) serviceRunIDs(ctx context.Context, root uuid.UUID) ([]uuid.UUID, error) {
	run, err := s.store.Orchestration().GetRun(ctx, root)
	if err != nil {
		return nil, err
	}
	ids := []uuid.UUID{root}
	seen := map[uuid.UUID]bool{root: true}
	issues := []uuid.UUID{run.RootIssueID}
	seenIssues := map[uuid.UUID]bool{run.RootIssueID: true}
	add := func(id uuid.UUID) {
		if !seen[id] {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	for pos := 0; pos < len(issues); pos++ {
		if len(issues) > 10000 {
			return nil, fmt.Errorf("invocation child graph exceeds 10000 issues")
		}
		parent := issues[pos]
		for offset := 0; ; offset += 1000 {
			children, err := s.store.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: run.Tenant, Namespace: run.Namespace, ParentID: &parent, Limit: 1000, Offset: offset})
			if err != nil {
				return nil, err
			}
			for _, child := range children {
				if !seenIssues[child.ID] {
					seenIssues[child.ID] = true
					issues = append(issues, child.ID)
				}
			}
			if len(children) < 1000 {
				break
			}
		}
		if pos > 0 {
			for offset := 0; ; offset += 1000 {
				runs, err := s.store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace, RootIssueID: parent, Limit: 1000, Offset: offset})
				if err != nil {
					return nil, err
				}
				for _, r := range runs {
					add(r.ID)
				}
				if len(runs) < 1000 {
					break
				}
			}
		}
	}
	for pos := 0; pos < len(ids); pos++ {
		if len(ids) > 10000 {
			return nil, fmt.Errorf("invocation child graph exceeds 10000 runs")
		}
		nodes, err := s.store.Orchestration().ListNodes(ctx, ids[pos])
		if err != nil {
			return nil, err
		}
		for _, n := range nodes {
			for offset := 0; ; offset += 1000 {
				runs, err := s.store.Orchestration().ListRuns(ctx, store.OrchestrationRunFilter{Tenant: run.Tenant, Namespace: run.Namespace, ParentNodeID: n.ID, Limit: 1000, Offset: offset})
				if err != nil {
					return nil, err
				}
				for _, r := range runs {
					if r.ParentRunID != nil && *r.ParentRunID == ids[pos] {
						add(r.ID)
					}
				}
				if len(runs) < 1000 {
					break
				}
			}
		}
	}
	return ids, nil
}
func (s *Server) serviceTasks(ctx context.Context, root uuid.UUID) ([]*model.AgentTask, error) {
	ids, err := s.serviceRunIDs(ctx, root)
	if err != nil {
		return nil, err
	}
	result := []*model.AgentTask{}
	for _, id := range ids {
		for offset := 0; ; offset += 1000 {
			items, err := s.store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: id, Limit: 1000, Offset: offset})
			if err != nil {
				return nil, err
			}
			result = append(result, items...)
			if len(items) < 1000 {
				break
			}
		}
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].CreatedAt.Equal(result[j].CreatedAt) {
			return result[i].ID.String() < result[j].ID.String()
		}
		return result[i].CreatedAt.Before(result[j].CreatedAt)
	})
	return result, nil
}

func (s *Server) serviceArtifactTargets(ctx context.Context, inv *model.EndpointInvocation) (map[string][]string, error) {
	targets := map[string][]string{"issue": {}, "agent-task": {}, "run": {}}
	if inv.IssueID != nil {
		targets["issue"] = append(targets["issue"], inv.IssueID.String())
	}
	if inv.RunID != nil {
		ids, err := s.serviceRunIDs(ctx, *inv.RunID)
		if err != nil {
			return nil, err
		}
		for _, id := range ids {
			targets["run"] = append(targets["run"], id.String())
		}
		tasks, err := s.serviceTasks(ctx, *inv.RunID)
		if err != nil {
			return nil, err
		}
		for _, task := range tasks {
			targets["issue"] = append(targets["issue"], task.IssueID.String())
			targets["agent-task"] = append(targets["agent-task"], task.ID.String())
		}
	}
	return targets, nil
}
func (s *Server) serviceArtifacts(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint) ([]*model.Artifact, error) {
	targets, err := s.serviceArtifactTargets(ctx, inv)
	if err != nil {
		return nil, err
	}
	seen := map[uuid.UUID]bool{}
	out := []*model.Artifact{}
	for kind, refs := range targets {
		for _, ref := range refs {
			items, err := s.store.Collaboration().ListArtifacts(ctx, ep.Tenant, ep.Namespace, kind, ref)
			if err != nil {
				return nil, err
			}
			for _, item := range items {
				if !seen[item.ID] {
					seen[item.ID] = true
					out = append(out, item)
				}
			}
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID.String() < out[j].ID.String() })
	return out, nil
}
