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

// Package scheduler performs durable admission and ordered runtime placement.
package scheduler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimebinding"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const AgingInterval = 10 * time.Minute
const MaxAgingBoost int32 = 50
const DefaultMaxTenantConcurrency = 1000
const DefaultMaxRunConcurrency = 100

// PermanentDispatchError tells the durable outbox that retrying the same
// immutable runtime policy cannot make progress. Capacity and availability
// errors remain ordinary retryable errors.
type PermanentDispatchError struct {
	code    string
	message string
}

func (e *PermanentDispatchError) Error() string { return e.message }

func (e *PermanentDispatchError) DispatchFailureCode() string { return e.code }

func exhaustedRuntimeCandidates(message string) error {
	return &PermanentDispatchError{code: "runtime_candidates_exhausted", message: message}
}

type Scheduler struct {
	Store                store.Store
	Resolver             *runtimebinding.Resolver
	NamespaceWeights     map[string]float64
	MaxTenantConcurrency int
	MaxRunConcurrency    int
	Now                  func() time.Time
}

func EffectivePriority(priority int32, createdAt, now time.Time) int32 {
	if now.Before(createdAt) {
		return priority
	}
	boost := int32(now.Sub(createdAt) / AgingInterval)
	if boost > MaxAgingBoost {
		boost = MaxAgingBoost
	}
	return priority + boost
}

func (s *Scheduler) DispatchNext(ctx context.Context, tenant string) (*runtimebinding.DispatchResult, error) {
	if s == nil || s.Store == nil {
		return nil, fmt.Errorf("scheduler store is required")
	}
	now := time.Now().UTC()
	if s.Now != nil {
		now = s.Now()
	}
	tasks, err := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: tenant, Status: controlmodel.AgentTaskQueued, Limit: 500})
	if err != nil {
		return nil, err
	}
	if len(tasks) == 0 {
		return nil, store.ErrNotFound
	}
	activeByNS := map[string]int{}
	active, err := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{Tenant: tenant, Limit: 5000})
	if err != nil {
		return nil, err
	}
	for _, attempt := range active {
		if !controlmodel.IsExecutionAttemptTerminal(attempt.State) {
			activeByNS[attempt.Namespace]++
		}
	}
	weights := s.NamespaceWeights
	weight := func(ns string) float64 {
		if weights != nil && weights[ns] > 0 {
			return weights[ns]
		}
		return 1
	}
	sort.SliceStable(tasks, func(i, j int) bool {
		left, right := float64(activeByNS[tasks[i].Namespace])/weight(tasks[i].Namespace), float64(activeByNS[tasks[j].Namespace])/weight(tasks[j].Namespace)
		if left != right {
			return left < right
		}
		lp, rp := EffectivePriority(tasks[i].Priority, tasks[i].CreatedAt, now), EffectivePriority(tasks[j].Priority, tasks[j].CreatedAt, now)
		if lp != rp {
			return lp > rp
		}
		li, _ := s.Store.Collaboration().GetIssue(ctx, tasks[i].IssueID)
		ri, _ := s.Store.Collaboration().GetIssue(ctx, tasks[j].IssueID)
		if li != nil && ri != nil && li.DueAt != nil && ri.DueAt != nil && !li.DueAt.Equal(*ri.DueAt) {
			return li.DueAt.Before(*ri.DueAt)
		}
		return tasks[i].CreatedAt.Before(tasks[j].CreatedAt)
	})
	var last error
	for _, task := range tasks {
		result, dispatchErr := s.DispatchTask(ctx, task.ID)
		if dispatchErr == nil {
			return result, nil
		}
		last = dispatchErr
	}
	if last == nil {
		last = store.ErrNotFound
	}
	return nil, last
}

// DispatchTask performs admission and placement for one durable task while
// preserving the same policy precedence as the fair queue selector.
func (s *Scheduler) DispatchTask(ctx context.Context, taskID uuid.UUID) (*runtimebinding.DispatchResult, error) {
	return s.dispatchTask(ctx, taskID, nil)
}

func (s *Scheduler) DispatchTaskCandidate(ctx context.Context, taskID uuid.UUID, override *controlmodel.RuntimeBindingCandidate) (*runtimebinding.DispatchResult, error) {
	return s.dispatchTask(ctx, taskID, override)
}

func (s *Scheduler) dispatchTask(ctx context.Context, taskID uuid.UUID, override *controlmodel.RuntimeBindingCandidate) (*runtimebinding.DispatchResult, error) {
	if s == nil || s.Store == nil {
		return nil, fmt.Errorf("scheduler store is required")
	}
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if task.Status != controlmodel.AgentTaskQueued {
		return nil, store.ErrConflict
	}
	if err := s.admitConcurrency(ctx, task); err != nil {
		return nil, err
	}
	policy, loadErr := s.Store.Orchestration().GetRuntimePolicy(ctx, task.Tenant, task.Namespace, task.AgentRef)
	if loadErr != nil && !errors.Is(loadErr, store.ErrNotFound) {
		return nil, loadErr
	}
	if policy != nil && policy.MaxConcurrency > 0 {
		count := 0
		active, listErr := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{Tenant: task.Tenant, Namespace: task.Namespace, Limit: 5000})
		if listErr != nil {
			return nil, listErr
		}
		for _, attempt := range active {
			if attempt.Namespace == task.Namespace && !controlmodel.IsExecutionAttemptTerminal(attempt.State) {
				other, _ := s.Store.Collaboration().GetAgentTask(ctx, attempt.AgentTaskID)
				if other != nil && other.AgentRef == task.AgentRef {
					count++
				}
			}
		}
		if count >= int(policy.MaxConcurrency) {
			return nil, fmt.Errorf("agent concurrency exhausted")
		}
	}
	if task.TeamID != nil {
		team, teamErr := runtimebinding.LoadRunTeamSnapshot(ctx, s.Store, task.OrchestrationRunID, *task.TeamID)
		if teamErr != nil {
			return nil, teamErr
		}
		if team.Policy.MaxActiveTasks > 0 {
			activeTasks, listErr := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{Tenant: task.Tenant, Namespace: task.Namespace, TeamID: *task.TeamID, Limit: 500})
			if listErr != nil {
				return nil, listErr
			}
			activeCount := 0
			for _, candidate := range activeTasks {
				if candidate.ID != task.ID && !controlmodel.IsAgentTaskTerminal(candidate.Status) && candidate.Status != controlmodel.AgentTaskQueued {
					activeCount++
				}
			}
			if activeCount >= int(team.Policy.MaxActiveTasks) {
				return nil, fmt.Errorf("Team concurrency exhausted")
			}
		}
		run, runErr := s.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
		if runErr != nil {
			return nil, runErr
		}
		usedTokens, usedCost := usageCounters(run.Usage)
		if team.Policy.MaxIssueTokens > 0 && usedTokens >= team.Policy.MaxIssueTokens {
			return nil, fmt.Errorf("Run token budget exhausted")
		}
		if team.Policy.MaxIssueCostMicros > 0 && usedCost >= team.Policy.MaxIssueCostMicros {
			return nil, fmt.Errorf("Run cost budget exhausted")
		}
	}
	now := time.Now().UTC()
	if s.Now != nil {
		now = s.Now()
	}
	if issue, issueErr := s.Store.Collaboration().GetIssue(ctx, task.IssueID); issueErr == nil && issue.DueAt != nil && now.After(*issue.DueAt) {
		return nil, fmt.Errorf("task deadline exceeded")
	}
	var candidate *controlmodel.RuntimeBindingCandidate
	var candidateErr error
	if override != nil {
		override.SelectionSource = "operator"
		available, availabilityErr := s.available(ctx, task, override)
		if availabilityErr != nil {
			return nil, availabilityErr
		}
		if !available {
			return nil, fmt.Errorf("requested runtime candidate is unavailable")
		}
		candidate = override
	} else {
		candidate, candidateErr = s.selectCandidate(ctx, task, policy)
	}
	if candidateErr != nil {
		return nil, candidateErr
	}
	resolver := s.Resolver
	if resolver == nil {
		resolver = &runtimebinding.Resolver{Store: s.Store}
	}
	return resolver.DispatchCandidate(ctx, task.ID, candidate)
}

func (s *Scheduler) admitConcurrency(ctx context.Context, task *controlmodel.AgentTask) error {
	if task.TeamID != nil && task.LeaderTask {
		leaderTasks, err := s.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
			Tenant: task.Tenant, Namespace: task.Namespace, RunID: task.OrchestrationRunID,
			NodeID: task.RunNodeID, TeamID: *task.TeamID, Limit: 500,
		})
		if err != nil {
			return err
		}
		for _, candidate := range leaderTasks {
			if candidate.ID == task.ID || !candidate.LeaderTask || candidate.AgentRef != task.AgentRef ||
				candidate.Status == controlmodel.AgentTaskQueued || controlmodel.IsAgentTaskTerminal(candidate.Status) {
				continue
			}
			return fmt.Errorf("Team leader follow-up already active")
		}
	}
	attempts, err := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{Tenant: task.Tenant, Limit: 10000})
	if err != nil {
		return err
	}
	tenantActive, runActive := 0, 0
	for _, attempt := range attempts {
		if controlmodel.IsExecutionAttemptTerminal(attempt.State) {
			continue
		}
		tenantActive++
		if attempt.RunID == task.OrchestrationRunID {
			runActive++
		}
	}
	maxTenant := s.MaxTenantConcurrency
	if maxTenant <= 0 {
		maxTenant = DefaultMaxTenantConcurrency
	}
	maxRun := s.MaxRunConcurrency
	if maxRun <= 0 {
		maxRun = DefaultMaxRunConcurrency
	}
	if tenantActive >= maxTenant {
		return fmt.Errorf("tenant concurrency exhausted")
	}
	if runActive >= maxRun {
		return fmt.Errorf("Run concurrency exhausted")
	}
	return nil
}

func usageCounters(raw json.RawMessage) (tokens, cost int64) {
	var usage struct {
		TotalTokens int64 `json:"totalTokens"`
		CostMicros  int64 `json:"costMicros"`
	}
	_ = json.Unmarshal(raw, &usage)
	return usage.TotalTokens, usage.CostMicros
}

func (s *Scheduler) selectCandidate(ctx context.Context, task *controlmodel.AgentTask, policy *controlmodel.AgentRuntimePolicy) (*controlmodel.RuntimeBindingCandidate, error) {
	var previous controlmodel.RuntimeDispatchSnapshot
	if len(task.RuntimeBinding) > 0 {
		if json.Unmarshal(task.RuntimeBinding, &previous) == nil && previous.Binding.Kind != "" && previous.SelectionSource == "node" {
			candidate := &controlmodel.RuntimeBindingCandidate{Binding: previous.Binding,
				RequiredCapabilities: previous.Capabilities, SecurityConstraints: previous.SecurityConstraints,
				SelectionSource: "node", CandidateIndex: previous.CandidateIndex}
			available, err := s.available(ctx, task, candidate)
			if err != nil || !available {
				if err != nil {
					return nil, err
				}
				return nil, fmt.Errorf("RunNode runtime override is unavailable")
			}
			return candidate, nil
		}
	}
	if task.TeamID != nil {
		team, err := runtimebinding.LoadRunTeamSnapshot(ctx, s.Store, task.OrchestrationRunID, *task.TeamID)
		if err != nil {
			return nil, err
		}
		for _, member := range team.Members {
			if member.AgentRef != task.AgentRef || task.TeamRole != "" && member.Role != task.TeamRole {
				continue
			}
			var memberPolicy controlmodel.RuntimeBindingPolicy
			if json.Unmarshal(member.RuntimeBindingPolicy, &memberPolicy) != nil || len(memberPolicy.Candidates) == 0 {
				break
			}
			return s.selectOrderedCandidate(ctx, task, previous, memberPolicy, "team")
		}
	}
	if policy == nil {
		return nil, fmt.Errorf("no runtime policy is configured for agent %q", task.AgentRef)
	}
	if policy.SelectionMode != "ordered" || len(policy.Candidates) == 0 {
		return nil, fmt.Errorf("runtime policy has no ordered candidates")
	}
	return s.selectOrderedCandidate(ctx, task, previous, controlmodel.RuntimeBindingPolicy{
		Candidates: policy.Candidates, SelectionMode: policy.SelectionMode,
		FallbackMode: policy.FallbackMode, RetryPolicy: policy.RetryPolicy,
	}, "policy")
}

func (s *Scheduler) selectOrderedCandidate(ctx context.Context, task *controlmodel.AgentTask,
	previous controlmodel.RuntimeDispatchSnapshot, policy controlmodel.RuntimeBindingPolicy,
	source string) (*controlmodel.RuntimeBindingCandidate, error) {
	if policy.SelectionMode != "ordered" || len(policy.Candidates) == 0 {
		return nil, fmt.Errorf("%s runtime policy has no ordered candidates", source)
	}
	start := 0
	if previous.SelectionSource == source && previous.CandidateIndex >= 0 {
		start = int(previous.CandidateIndex)
		attempts, err := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentTaskID: task.ID, Limit: 1000})
		if err != nil {
			return nil, err
		}
		failures := 0
		for _, attempt := range attempts {
			var snapshot controlmodel.RuntimeDispatchSnapshot
			if attempt.State == controlmodel.ExecutionFailed && json.Unmarshal(attempt.RuntimeBinding, &snapshot) == nil &&
				snapshot.SelectionSource == source && snapshot.CandidateIndex == previous.CandidateIndex {
				failures++
			}
		}
		if failures >= infrastructureAttempts(policy.RetryPolicy) {
			if policy.FallbackMode != "fresh" {
				return nil, exhaustedRuntimeCandidates(
					"preferred runtime candidate exhausted and fresh fallback is disabled")
			}
			start++
		}
	}
	if start >= len(policy.Candidates) {
		return nil, exhaustedRuntimeCandidates(
			fmt.Sprintf("all %s runtime candidates are exhausted", source))
	}
	candidate := policy.Candidates[start]
	candidate.SelectionSource = source
	candidate.CandidateIndex = int32(start)
	available, err := s.available(ctx, task, &candidate)
	if err != nil {
		return nil, err
	}
	if !available {
		return nil, fmt.Errorf("%s runtime candidate %d is unavailable", source, start)
	}
	return &candidate, nil
}

func infrastructureAttempts(raw json.RawMessage) int {
	limit := 3
	var policy struct {
		MaxInfrastructureAttempts int `json:"maxInfrastructureAttempts"`
		MaxAttemptsPerCandidate   int `json:"maxAttemptsPerCandidate"`
	}
	if json.Unmarshal(raw, &policy) == nil {
		if policy.MaxInfrastructureAttempts > 0 {
			limit = policy.MaxInfrastructureAttempts
		} else if policy.MaxAttemptsPerCandidate > 0 {
			limit = policy.MaxAttemptsPerCandidate
		}
	}
	return limit
}

func (s *Scheduler) available(ctx context.Context, task *controlmodel.AgentTask, candidate *controlmodel.RuntimeBindingCandidate) (bool, error) {
	if err := candidate.Binding.Validate(); err != nil {
		return false, err
	}
	switch candidate.Binding.Kind {
	case controlmodel.DataPlaneManaged:
		return controlmodel.RuntimeSecurityMatches(candidate.Binding.Kind, nil, candidate.SecurityConstraints), nil
	case controlmodel.DataPlaneExternalApplication:
		agentID, err := uuid.Parse(task.AgentRef)
		if err != nil {
			return false, fmt.Errorf("AgentTask agentRef must be an agentId: %w", err)
		}
		instances, err := s.Store.RuntimeRegistry().ListAgentInstances(ctx, task.Tenant, task.Namespace, agentID)
		if err != nil {
			return false, err
		}
		for _, instance := range instances {
			if instance.BindingID != candidate.Binding.BindingID {
				continue
			}
			if candidate.Binding.InstanceSelector["instance"] != "" && candidate.Binding.InstanceSelector["instance"] != instance.InstanceKey {
				continue
			}
			if instance.Health == controlmodel.RuntimeHealthHealthy && (instance.Capacity <= 0 || instance.ActiveSessions < instance.Capacity) &&
				capabilitiesMatch(instance.Capabilities, candidate.RequiredCapabilities) &&
				controlmodel.RuntimeSecurityMatches(candidate.Binding.Kind, instance.Labels, candidate.SecurityConstraints) {
				return true, nil
			}
		}
		return false, nil
	case controlmodel.DataPlaneHostedRuntime:
		profile, err := s.Store.RuntimeRegistry().GetRuntimeProfileByID(ctx, candidate.Binding.RuntimeProfileID)
		if err != nil || profile.Tenant != task.Tenant || profile.Namespace != task.Namespace {
			return false, nil
		}
		pool, err := s.Store.RuntimeRegistry().GetRuntimePoolByID(ctx, candidate.Binding.RuntimePoolID)
		if err != nil || pool.Tenant != task.Tenant || pool.Namespace != task.Namespace {
			return false, nil
		}
		hosts, err := s.Store.RuntimeRegistry().ListRuntimeHosts(ctx, task.Tenant, task.Namespace, pool.Name, controlmodel.RuntimeHostOnline)
		if err != nil {
			return false, err
		}
		for _, host := range hosts {
			if (host.Capacity <= 0 || host.Active < host.Capacity) &&
				controlmodel.RuntimeHostMatchesProfile(host.Capabilities, profile) &&
				controlmodel.RuntimeHostMatchesPool(host.Labels, pool) &&
				capabilitiesMatch(host.Capabilities, candidate.RequiredCapabilities) &&
				controlmodel.RuntimeSecurityMatches(candidate.Binding.Kind, host.Labels, candidate.SecurityConstraints) {
				return true, nil
			}
		}
		return false, nil
	default:
		return false, nil
	}
}

func capabilitiesMatch(actual, required json.RawMessage) bool {
	return controlmodel.JSONContains(actual, required)
}

var _ = errors.Is
