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

package automation

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func executionRule(t *testing.T, svc *Service, mode, target string) *controlmodel.Automation {
	t.Helper()
	ctx := context.Background()
	scope := "automation-test-" + uuid.NewString()
	agent, err := svc.Store.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: scope, Namespace: "default", AgentKey: "worker", DisplayName: "Worker", Status: controlmodel.AgentActive, OwnerType: "human", OwnerRef: "owner"})
	if err != nil {
		t.Fatal(err)
	}
	ref := agent.ID.String()
	kind := controlmodel.AssigneeAgent
	if target == "team" {
		team, err := svc.Store.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{Tenant: scope, Namespace: "default", Name: "Research team", LeaderAgentRef: ref, Status: controlmodel.TeamActive})
		if err != nil {
			t.Fatal(err)
		}
		ref = team.ID.String()
		kind = controlmodel.AssigneeTeam
	}
	rule, err := svc.Create(ctx, &controlmodel.Automation{Tenant: scope, Namespace: "default", Name: "Daily research", Description: "Keep this description", Enabled: true, CreatedBy: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}, Execution: &controlmodel.AutomationExecution{Runbook: "Read sources and produce a concise report.", AssigneeType: kind, AssigneeRef: ref, OutputMode: mode}, Triggers: []controlmodel.AutomationTrigger{{ID: uuid.New(), Type: controlmodel.AutomationTriggerCron, Enabled: true, Schedule: "0 9 * * 1-5", Timezone: "Asia/Shanghai"}}})
	if err != nil {
		t.Fatal(err)
	}
	return rule
}
func completeAutomationTask(t *testing.T, svc *Service, id uuid.UUID) {
	t.Helper()
	ctx := context.Background()
	repo := svc.Store.Collaboration()
	task, err := repo.GetAgentTask(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	task, err = repo.ClaimAgentTask(ctx, store.TaskClaim{TaskID: id, ExpectedVersion: task.Version, SessionID: "automation-test-session"})
	if err != nil {
		t.Fatal(err)
	}
	ids := []uuid.UUID{}
	for _, input := range task.Inputs {
		ids = append(ids, input.ID)
	}
	if _, err = repo.AcknowledgeTaskInputs(ctx, id, ids); err != nil {
		t.Fatal(err)
	}
	task, err = repo.StartAgentTask(ctx, id, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = repo.CompleteAgentTask(ctx, id, store.TaskCompletion{ExpectedVersion: task.Version, ProcessedInputIDs: ids, Result: json.RawMessage(`{"summary":"Research complete"}`), Summary: "Research complete"}); err != nil {
		t.Fatal(err)
	}
}
func automationExecutionSuite(t *testing.T, svc *Service) {
	ctx := context.Background()
	repo := svc.Store.Collaboration()
	for _, mode := range []string{"create_issue", "run_only"} {
		for _, target := range []string{"agent", "team"} {
			t.Run(mode+"_"+target, func(t *testing.T) {
				rule := executionRule(t, svc, mode, target)
				input := json.RawMessage(`{"event":"build.completed","build":42}`)
				run, err := svc.Trigger(ctx, rule.ID, "manual", "first", input)
				if err != nil {
					t.Fatal(err)
				}
				if run.Status != controlmodel.AutomationRunQueued || run.IssueID != nil {
					t.Fatalf("not durably queued: %+v", run)
				}
				duplicate, err := svc.Trigger(ctx, rule.ID, "retry", "first", input)
				if err != nil || duplicate.ID != run.ID {
					t.Fatalf("idempotency: %v", err)
				}
				if _, err = svc.Trigger(ctx, rule.ID, "retry", "first", json.RawMessage(`{"build":43}`)); err != store.ErrConflict {
					t.Fatalf("conflicting payload accepted: %v", err)
				}
				rule.Execution.Runbook = "A changed runbook for future executions"
				rule, err = svc.Update(ctx, rule, rule.Version)
				if err != nil {
					t.Fatal(err)
				}
				run, err = svc.ProcessRun(ctx, run)
				if err != nil {
					t.Fatal(err)
				}
				if run.IssueID == nil || run.AgentTaskID == nil || controlmodel.IsAutomationRunTerminal(run.Status) {
					t.Fatalf("missing execution or prematurely completed: %+v", run)
				}
				issue, err := repo.GetIssue(ctx, *run.IssueID)
				if err != nil {
					t.Fatal(err)
				}
				if !strings.Contains(issue.Description, "produce a concise report") || !strings.Contains(issue.Description, "42") || strings.Contains(issue.Description, "changed runbook") {
					t.Fatalf("snapshot or trigger input lost: %s", issue.Description)
				}
				if (mode == "run_only") != (issue.Visibility == controlmodel.IssueVisibilityOperational) {
					t.Fatalf("wrong visibility: %+v", issue)
				}
				task, err := repo.GetAgentTask(ctx, *run.AgentTaskID)
				if err != nil {
					t.Fatal(err)
				}
				if task.AccountableHumanRef != "owner" {
					t.Fatalf("missing responsible human: %+v", task)
				}
				if target == "team" {
					if !task.LeaderTask || task.TeamID == nil {
						t.Fatal("Team did not enter its leader path")
					}
					engine, err := svc.Store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
					if err != nil {
						t.Fatal(err)
					}
					if engine.State == controlmodel.RunPlanned {
						engine, err = svc.Store.Orchestration().TransitionRun(ctx, engine.ID, engine.Version, controlmodel.RunRunning, nil, "", "")
						if err != nil {
							t.Fatal(err)
						}
					}
					_, err = svc.Store.Orchestration().TransitionRun(ctx, engine.ID, engine.Version, controlmodel.RunWaiting, nil, "", "")
					if err != nil {
						t.Fatal(err)
					}
					run, err = svc.ProcessRun(ctx, run)
					if err != nil || run.Status != controlmodel.AutomationRunWaiting {
						t.Fatalf("Team wait state not propagated: %+v %v", run, err)
					}
					return
				}
				completeAutomationTask(t, svc, task.ID)
				run, err = svc.ProcessRun(ctx, run)
				if err != nil {
					t.Fatal(err)
				}
				if mode == "run_only" {
					if run.Status != controlmodel.AutomationRunCompleted {
						t.Fatalf("not complete: %+v", run)
					}
				} else {
					if run.Status != controlmodel.AutomationRunWaiting || run.WaitReason != "review" || run.ExecutionCompletedAt == nil {
						t.Fatalf("review not preserved: %+v", run)
					}
					next, err := svc.Trigger(ctx, rule.ID, "manual", "second", nil)
					if err != nil || next.Status != controlmodel.AutomationRunQueued {
						t.Fatalf("review blocked next schedule: %v %+v", err, next)
					}
				}
				if !strings.Contains(string(run.Output), "Research complete") {
					t.Fatalf("missing result: %s", run.Output)
				}
			})
		}
	}
	t.Run("recover_partial_dispatch", func(t *testing.T) {
		rule := executionRule(t, svc, "run_only", "agent")
		run, err := svc.Trigger(ctx, rule.ID, "manual", "crash", nil)
		if err != nil {
			t.Fatal(err)
		}
		claimed, err := repo.ClaimAutomationRun(ctx, run.ID, time.Now().UTC(), time.Millisecond)
		if err != nil {
			t.Fatal(err)
		}
		if err = svc.dispatch(ctx, claimed); err != nil {
			t.Fatal(err)
		} // Downstream committed; run linkage did not.
		stored, err := repo.GetAutomationRun(ctx, run.ID)
		if err != nil || stored.IssueID != nil {
			t.Fatal("crash point was not before run linkage")
		}
		recovered := &Service{Store: svc.Store, Now: func() time.Time { return time.Now().Add(time.Minute) }}
		run, err = recovered.ProcessRun(ctx, stored)
		if err != nil {
			t.Fatal(err)
		}
		issues, err := repo.ListIssues(ctx, store.IssueFilter{Tenant: rule.Tenant, Namespace: rule.Namespace, Limit: 100})
		if err != nil || len(issues) != 1 {
			t.Fatalf("duplicate issue after recovery: %d %v", len(issues), err)
		}
		tasks, err := repo.ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: *run.IssueID, Limit: 100})
		if err != nil || len(tasks) != 1 {
			t.Fatalf("duplicate task after recovery: %d %v", len(tasks), err)
		}
	})
	t.Run("concurrent_schedule_acceptance", func(t *testing.T) {
		rule := executionRule(t, svc, "run_only", "agent")
		due := time.Now().UTC().Add(-time.Second)
		rule.NextRunAt = &due
		rule.Triggers[0].NextRunAt = &due
		rule.Triggers[0].ID = uuid.New()
		rule, err := repo.UpdateAutomation(ctx, rule, rule.Version)
		if err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		errs := make(chan error, 8)
		for i := 0; i < 8; i++ {
			wg.Add(1)
			go func() { defer wg.Done(); _, e := svc.RunDue(ctx, time.Now().UTC(), 1000); errs <- e }()
		}
		wg.Wait()
		close(errs)
		for e := range errs {
			if e != nil {
				t.Fatal(e)
			}
		}
		runs, err := repo.ListAutomationRuns(ctx, rule.ID, 100, 0)
		if err != nil || len(runs) != 1 {
			t.Fatalf("duplicate scheduled runs: %d %v", len(runs), err)
		}
		after, err := repo.GetAutomation(ctx, rule.ID)
		if err != nil || after.NextRunAt == nil || !after.NextRunAt.After(due) || after.Version != rule.Version {
			t.Fatalf("cursor/version not atomically preserved: %+v %v", after, err)
		}
	})
	t.Run("queue_and_skip", func(t *testing.T) {
		for _, policy := range []string{"skip", "queue"} {
			rule := executionRule(t, svc, "run_only", "agent")
			rule.Execution.ConcurrencyPolicy = policy
			rule, err := svc.Update(ctx, rule, rule.Version)
			if err != nil {
				t.Fatal(err)
			}
			first, err := svc.Trigger(ctx, rule.ID, "manual", "first", nil)
			if err != nil {
				t.Fatal(err)
			}
			second, err := svc.Trigger(ctx, rule.ID, "manual", "second", nil)
			if err != nil {
				t.Fatal(err)
			}
			if policy == "skip" {
				if second.Status != controlmodel.AutomationRunSkipped {
					t.Fatal("overlapping run was not skipped")
				}
				continue
			}
			if _, err = svc.ProcessRun(ctx, second); err != store.ErrConflict {
				t.Fatalf("queue ran out of order: %v", err)
			}
			if _, err = svc.Cancel(ctx, first.ID); err != nil {
				t.Fatal(err)
			}
			second, err = repo.GetAutomationRun(ctx, second.ID)
			if err != nil {
				t.Fatal(err)
			}
			second, err = svc.ProcessRun(ctx, second)
			if err != nil || second.IssueID == nil {
				t.Fatalf("queue did not resume: %v", err)
			}
		}
	})
	t.Run("disabled_target_and_scope", func(t *testing.T) {
		rule := executionRule(t, svc, "run_only", "agent")
		run, err := svc.Trigger(ctx, rule.ID, "manual", "first", nil)
		if err != nil {
			t.Fatal(err)
		}
		id, _ := uuid.Parse(rule.Execution.AssigneeRef)
		agent, _ := svc.Store.AgentCatalog().GetAgent(ctx, id)
		agent.Status = controlmodel.AgentDisabled
		if _, err = svc.Store.AgentCatalog().UpdateAgent(ctx, agent, agent.Version); err != nil {
			t.Fatal(err)
		}
		run, err = svc.ProcessRun(ctx, run)
		if err != nil || run.Status != controlmodel.AutomationRunFailed || run.ErrorCode != "target_unavailable" {
			t.Fatalf("disabled Agent was dispatched: %+v %v", run, err)
		}
		items, err := repo.ListInbox(ctx, store.InboxFilter{Tenant: rule.Tenant, Namespace: rule.Namespace, RecipientRef: "owner", Limit: 100})
		if err != nil || len(items) != 1 || items[0].Type != "automation_failure" || !strings.Contains(items[0].Body, run.ID.String()) {
			t.Fatalf("failure notification: %+v %v", items, err)
		}

	})
	t.Run("queue_deadline_and_delivery_recovery", func(t *testing.T) {
		rule := executionRule(t, svc, "run_only", "agent")
		run, err := svc.Trigger(ctx, rule.ID, "manual", "timeout", nil)
		if err != nil {
			t.Fatal(err)
		}
		future := &Service{Store: svc.Store, Now: func() time.Time { return run.CreatedAt.Add(2 * time.Hour) }}
		timed, err := future.ProcessRun(ctx, run)
		if err != nil || timed.Status != controlmodel.AutomationRunFailed || timed.ErrorCode != "queue_timeout" {
			t.Fatalf("queue timeout: %+v %v", timed, err)
		}
		rule.Triggers = []controlmodel.AutomationTrigger{{ID: uuid.New(), Type: controlmodel.AutomationTriggerWebhook, Enabled: true}}
		rule, err = svc.Update(ctx, rule, rule.Version)
		if err != nil {
			t.Fatal(err)
		}
		delivery, _, err := repo.SaveAutomationDelivery(ctx, &controlmodel.AutomationDelivery{ID: uuid.New(), AutomationID: rule.ID, TriggerID: rule.Triggers[0].ID, IdempotencyKey: "crash-recovery", Input: json.RawMessage(`{"build":42}`), Status: "queued"})
		if err != nil {
			t.Fatal(err)
		}
		// Simulate accepted Run committed, then process death before delivery status update.
		accepted, err := svc.TriggerSource(ctx, rule.ID, delivery.TriggerID, "webhook", delivery.Event, delivery.ID.String(), delivery.Input, nil)
		if err != nil {
			t.Fatal(err)
		}
		recovered, err := (&Service{Store: svc.Store}).ProcessDelivery(ctx, delivery)
		if err != nil || recovered.RunID == nil || *recovered.RunID != accepted.ID {
			t.Fatalf("delivery recovery: %+v %v", recovered, err)
		}
		first, err := svc.ReplayDelivery(ctx, rule.ID, delivery.ID, "repeat-key")
		if err != nil {
			t.Fatal(err)
		}
		second, err := svc.ReplayDelivery(ctx, rule.ID, delivery.ID, "repeat-key")
		if err != nil || second.ID != first.ID {
			t.Fatalf("replay duplicated: %v", err)
		}
	})

}
func TestAutomationExecutionMemory(t *testing.T) {
	svc, _ := newTestService(t)
	automationExecutionSuite(t, svc)
}
func TestAutomationExecutionPostgres(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverPostgres, PostgresDSN: dsn})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	automationExecutionSuite(t, &Service{Store: st})
}
func TestSchedulePreviewTimezoneAndDST(t *testing.T) {
	for _, tc := range []struct{ schedule, zone, after, want string }{
		{"0 9 * * 1-5", "Asia/Shanghai", "2026-09-04T02:00:00Z", "2026-09-07T01:00:00Z"},
		{"0 9 * * *", "UTC", "2026-09-07T08:59:00Z", "2026-09-07T09:00:00Z"},
		{"30 2 * * *", "America/New_York", "2026-03-08T05:00:00Z", "2026-03-09T06:30:00Z"},
	} {
		t.Run(tc.zone+tc.schedule, func(t *testing.T) {
			after, _ := time.Parse(time.RFC3339, tc.after)
			times, err := Preview(tc.schedule, tc.zone, after, 3)
			if err != nil || times[0].Format(time.RFC3339) != tc.want {
				t.Fatalf("preview: %v %v", times, err)
			}
		})
	}
	for _, bad := range []string{"* * * * * *", "@every 1s", "bad", "0 0 31 2 *"} {
		if _, err := Preview(bad, "UTC", time.Now(), 1); err == nil {
			t.Fatal(fmt.Sprintf("accepted %q", bad))
		}
	}
	if _, err := Preview("0 9 * * *", "Not/AZone", time.Now(), 1); err == nil {
		t.Fatal("invalid timezone accepted")
	}
}
