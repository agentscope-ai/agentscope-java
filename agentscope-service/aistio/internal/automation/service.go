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

// Package automation owns durable acceptance, execution and reconciliation of recurring work.
package automation

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"strings"
	"time"
)

type ValidationError struct{ error }

type Service struct {
	Store store.Store
	Now   func() time.Time
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now().UTC()
	}
	return time.Now().UTC()
}

// NormalizeLegacy exposes existing rules without changing the meaning of old runs.
func NormalizeLegacy(rule *controlmodel.Automation) {
	rule.WebhookConfigured = rule.WebhookSecretHash != ""
	if rule.Triggers != nil {
		return
	}
	rule.Triggers = []controlmodel.AutomationTrigger{}
	if rule.TriggerType == "" || rule.TriggerType == "manual" {
		return
	}
	t := controlmodel.AutomationTrigger{ID: uuid.NewSHA1(rule.ID, []byte("primary-trigger")), Type: rule.TriggerType, Enabled: true, NextRunAt: rule.NextRunAt, Timezone: "UTC"}
	var config struct {
		Schedule string   `json:"schedule"`
		Timezone string   `json:"timezone"`
		Events   []string `json:"events"`
	}
	_ = json.Unmarshal(rule.TriggerConfig, &config)
	t.Schedule = config.Schedule
	t.Events = config.Events
	if config.Timezone != "" {
		t.Timezone = config.Timezone
	}
	rule.Triggers = append(rule.Triggers, t)
}

func (s *Service) ValidateTarget(ctx context.Context, rule *controlmodel.Automation) error {
	e := rule.Execution
	if e == nil {
		return s.validateLegacyTarget(ctx, rule)
	}
	id, err := uuid.Parse(e.AssigneeRef)
	if err != nil {
		return fmt.Errorf("select a registered Agent or Team")
	}
	if e.AssigneeType == controlmodel.AssigneeTeam {
		team, err := s.Store.Collaboration().GetTeam(ctx, id)
		if err != nil || team.Tenant != rule.Tenant || team.Namespace != rule.Namespace || team.Status != "active" {
			return fmt.Errorf("selected Team is unavailable in this namespace")
		}
		id, err = uuid.Parse(team.LeaderAgentRef)
		if err != nil {
			return fmt.Errorf("Team leader is invalid")
		}
	} else if e.AssigneeType != controlmodel.AssigneeAgent {
		return fmt.Errorf("assigneeType must be agent or team")
	}
	agent, err := s.Store.AgentCatalog().GetAgent(ctx, id)
	if err != nil || agent.Tenant != rule.Tenant || agent.Namespace != rule.Namespace || agent.Status != controlmodel.AgentActive || agent.ArchivedAt != nil {
		return fmt.Errorf("selected Agent is unavailable in this namespace")
	}
	return nil
}
func (s *Service) validate(ctx context.Context, rule *controlmodel.Automation) error {
	if strings.TrimSpace(rule.Name) == "" || len(rule.Name) > 200 {
		return fmt.Errorf("name must contain 1 to 200 characters")
	}
	if rule.Execution != nil {
		e := rule.Execution
		e.Runbook = strings.TrimSpace(e.Runbook)
		if e.Runbook == "" || len(e.Runbook) > 100000 {
			return fmt.Errorf("runbook must contain 1 to 100000 characters")
		}
		if e.OutputMode == "" {
			e.OutputMode = "create_issue"
		}
		if e.OutputMode != "create_issue" && e.OutputMode != "run_only" {
			return fmt.Errorf("invalid outputMode")
		}
		if e.CompletionPolicy == "" {
			e.CompletionPolicy = controlmodel.IssueCompletionReview
		}
		if e.OutputMode == "run_only" {
			e.CompletionPolicy = controlmodel.IssueCompletionAutomatic
		}
		if e.CompletionPolicy != controlmodel.IssueCompletionAutomatic && e.CompletionPolicy != controlmodel.IssueCompletionReview {
			return fmt.Errorf("invalid completionPolicy")
		}
		if e.ConcurrencyPolicy == "" {
			e.ConcurrencyPolicy = "skip"
		}
		if e.ConcurrencyPolicy != "skip" && e.ConcurrencyPolicy != "queue" {
			return fmt.Errorf("invalid concurrencyPolicy")
		}
		if e.QueueTimeoutSeconds == 0 {
			e.QueueTimeoutSeconds = 3600
		}
		if e.RunTimeoutSeconds == 0 {
			e.RunTimeoutSeconds = 3600
		}
		if e.QueueTimeoutSeconds < 60 || e.QueueTimeoutSeconds > 604800 || e.RunTimeoutSeconds < 60 || e.RunTimeoutSeconds > 604800 {
			return fmt.Errorf("timeouts must be between 60 and 604800 seconds")
		}
		if len(e.ContextRefs) > 0 {
			var refs []json.RawMessage
			if json.Unmarshal(e.ContextRefs, &refs) != nil {
				return fmt.Errorf("contextRefs must be an array")
			}
		}
		if len(e.Subscribers) > 50 {
			return fmt.Errorf("at most 50 subscribers are allowed")
		}
		if err := s.ValidateTarget(ctx, rule); rule.Enabled && err != nil {
			return err
		}
		rule.ActionType = controlmodel.AutomationCreateIssue
		rule.ActionConfig, _ = json.Marshal(IssueAction{Title: rule.Name, Description: e.Runbook, AssigneeType: e.AssigneeType, AssigneeRef: e.AssigneeRef, ContextRefs: e.ContextRefs})
	} else if err := validateAction(rule.ActionType, rule.ActionConfig); err != nil {
		return err
	}
	if rule.Execution == nil && rule.Enabled {
		if err := s.ValidateTarget(ctx, rule); err != nil {
			return err
		}
	}
	NormalizeLegacy(rule)
	if len(rule.Triggers) > 10 {
		return fmt.Errorf("at most 10 triggers are allowed")
	}
	ids := map[uuid.UUID]bool{}
	for i := range rule.Triggers {
		t := &rule.Triggers[i]
		if t.ID == uuid.Nil {
			t.ID = uuid.New()
		}
		if ids[t.ID] {
			return fmt.Errorf("duplicate trigger id")
		}
		ids[t.ID] = true
		switch t.Type {
		case controlmodel.AutomationTriggerCron:
			if t.Timezone == "" {
				t.Timezone = "UTC"
			}
			if _, err := Preview(t.Schedule, t.Timezone, s.now(), 1); err != nil {
				return err
			}
		case controlmodel.AutomationTriggerWebhook:
			if len(t.Events) > 50 {
				return fmt.Errorf("at most 50 event filters are allowed")
			}
		case controlmodel.AutomationTriggerChannel:
			if rule.Execution != nil {
				return fmt.Errorf("channel triggers are not implemented")
			}
		default:
			return fmt.Errorf("unsupported trigger type")
		}
	}
	if len(rule.Triggers) > 0 {
		rule.TriggerType = rule.Triggers[0].Type
		rule.TriggerConfig, _ = json.Marshal(rule.Triggers[0])
	} else {
		rule.TriggerType = "manual"
		rule.TriggerConfig = []byte(`{}`)
	}
	return nil
}
func (s *Service) Create(ctx context.Context, rule *controlmodel.Automation) (*controlmodel.Automation, error) {
	if s == nil || s.Store == nil || rule == nil {
		return nil, fmt.Errorf("automation store and rule are required")
	}
	if rule.ID == uuid.Nil {
		rule.ID = uuid.New()
	}
	if err := s.validate(ctx, rule); err != nil {
		return nil, ValidationError{err}
	}
	for i := range rule.Triggers {
		t := &rule.Triggers[i]
		t.LastFiredAt = nil
		t.NextRunAt = nil
		if rule.Enabled && t.Enabled && t.Type == controlmodel.AutomationTriggerCron {
			times, _ := Preview(t.Schedule, t.Timezone, s.now(), 1)
			t.NextRunAt = &times[0]
		}
	}
	refreshNext(rule)
	return s.Store.Collaboration().CreateAutomation(ctx, rule)
}
func (s *Service) Update(ctx context.Context, rule *controlmodel.Automation, version int64) (*controlmodel.Automation, error) {
	if version <= 0 {
		return nil, fmt.Errorf("positive expectedVersion is required")
	}
	old, err := s.Store.Collaboration().GetAutomation(ctx, rule.ID)
	if err != nil {
		return nil, err
	}
	if old.ArchivedAt != nil {
		return nil, fmt.Errorf("archived automation cannot be edited")
	}
	NormalizeLegacy(old)
	if err = s.validate(ctx, rule); err != nil {
		return nil, ValidationError{err}
	}
	for i := range rule.Triggers {
		t := &rule.Triggers[i]
		t.NextRunAt = nil
		t.LastFiredAt = nil
		for _, prior := range old.Triggers {
			if prior.ID == t.ID {
				t.LastFiredAt = prior.LastFiredAt
				if old.Enabled && rule.Enabled && prior.Enabled && t.Enabled && prior.Type == t.Type && prior.Schedule == t.Schedule && prior.Timezone == t.Timezone {
					t.NextRunAt = prior.NextRunAt
				}
			}
		}
		if rule.Enabled && t.Enabled && t.Type == controlmodel.AutomationTriggerCron && t.NextRunAt == nil {
			times, _ := Preview(t.Schedule, t.Timezone, s.now(), 1)
			t.NextRunAt = &times[0]
		}
	}
	refreshNext(rule)
	return s.Store.Collaboration().UpdateAutomation(ctx, rule, version)
}
func (s *Service) Trigger(ctx context.Context, id uuid.UUID, ref, key string, input json.RawMessage) (*controlmodel.AutomationRun, error) {
	return s.TriggerSource(ctx, id, uuid.Nil, "manual", ref, key, input, nil)
}
func (s *Service) TriggerSource(ctx context.Context, id, triggerID uuid.UUID, source, ref, key string, input json.RawMessage, rerunOf *uuid.UUID) (*controlmodel.AutomationRun, error) {
	if strings.TrimSpace(key) == "" || len(key) > 200 {
		return nil, fmt.Errorf("idempotency key is required and must not exceed 200 characters")
	}
	rule, err := s.Store.Collaboration().GetAutomation(ctx, id)
	if err != nil {
		return nil, err
	}
	NormalizeLegacy(rule)
	run := &controlmodel.AutomationRun{AutomationID: id, Tenant: rule.Tenant, Namespace: rule.Namespace, TriggerType: rule.TriggerType, TriggerRef: ref, IdempotencyKey: source + ":" + key, Input: input}
	run.Snapshot = rule
	run.Source = source
	run.TriggerID = triggerID
	run.RerunOf = rerunOf
	run, _, err = s.Store.Collaboration().AdmitAutomationRun(ctx, store.AutomationAdmission{Run: run, ExpectedVersion: rule.Version})
	return run, err
}
func (s *Service) RunDue(ctx context.Context, now time.Time, limit int) (int, error) {
	enabled := true
	rules, err := s.Store.Collaboration().ListAutomations(ctx, store.AutomationFilter{Enabled: &enabled, DueBefore: &now, Limit: limit})
	if err != nil {
		return 0, err
	}
	count := 0
	for _, rule := range rules {
		if rule.Triggers == nil {
			NormalizeLegacy(rule)
			rule, err = s.Store.Collaboration().UpdateAutomation(ctx, rule, rule.Version)
			if err == store.ErrConflict {
				continue
			}
			if err != nil {
				return count, err
			}
		}
		for _, t := range rule.Triggers {
			if !t.Enabled || t.Type != controlmodel.AutomationTriggerCron || t.NextRunAt == nil || t.NextRunAt.After(now) {
				continue
			}
			schedule, err := ParseSchedule(t.Schedule, t.Timezone)
			if err != nil {
				return count, err
			}
			observed := *t.NextRunAt
			planned := observed
			floor := now.Add(-24 * time.Hour)
			if planned.Before(floor) {
				planned = schedule.Next(floor)
			}
			expired := planned.IsZero() || planned.After(now)
			if expired {
				planned = observed
			} else {
				for n := schedule.Next(planned); !n.IsZero() && !n.After(now); n = schedule.Next(planned) {
					planned = n
				}
			}
			next := schedule.Next(now)
			if next.IsZero() {
				return count, fmt.Errorf("schedule has no future occurrence")
			}
			run := &controlmodel.AutomationRun{AutomationID: rule.ID, Tenant: rule.Tenant, Namespace: rule.Namespace, TriggerType: t.Type, IdempotencyKey: "cron:" + t.ID.String() + ":" + planned.UTC().Format(time.RFC3339Nano)}
			run.Snapshot = rule
			run.Source = "schedule"
			run.TriggerID = t.ID
			run.ScheduledAt = &planned
			if expired {
				run.ErrorCode = "misfire_expired"
				run.ErrorMessage = "The missed occurrence is outside the 24 hour catch-up window."
			}
			_, fresh, e := s.Store.Collaboration().AdmitAutomationRun(ctx, store.AutomationAdmission{Run: run, ExpectedVersion: rule.Version, NextRunAt: &next, ObservedScheduledAt: &observed})
			if e != nil && e != store.ErrConflict {
				return count, e
			}
			if e == nil && fresh {
				count++
			}
		}
	}
	return count, nil
}
