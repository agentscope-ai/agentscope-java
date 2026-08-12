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

package controller

import (
	"context"
	"encoding/json"
	"fmt"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/client-go/tools/record"
	"k8s.io/client-go/util/retry"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

// TeamEventReport is a neutral representation of an upstream team event.
// Decoupled from the asdp proto types.
type TeamEventReport struct {
	TeamID     string
	EventType  string
	MemberName string
	TaskID     string
	Detail     map[string]string
}

// TeamEventSink processes upstream team events from data plane instances.
type TeamEventSink struct {
	client    client.Client
	taskStore *team.TaskStore
	recorder  record.EventRecorder
	router    *team.MessageRouter
}

// NewTeamEventSink creates a new TeamEventSink.
func NewTeamEventSink(c client.Client, ts *team.TaskStore, rec record.EventRecorder) *TeamEventSink {
	return &TeamEventSink{
		client:    c,
		taskStore: ts,
		recorder:  rec,
	}
}

// SetMessageRouter wires lead notification for task transitions reported over ASDP.
func (s *TeamEventSink) SetMessageRouter(r *team.MessageRouter) {
	s.router = r
}

// ParseDetail unmarshals raw JSON bytes into a TeamEventReport Detail map.
func ParseDetail(raw []byte) map[string]string {
	if len(raw) == 0 {
		return nil
	}
	m := make(map[string]string)
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil
	}
	return m
}

// HandleEvent processes a team event by type.
func (s *TeamEventSink) HandleEvent(ctx context.Context, namespace string, evt *TeamEventReport) {
	logger := log.FromContext(ctx).WithValues("team", evt.TeamID, "eventType", evt.EventType, "member", evt.MemberName)
	metrics.RecordTeamMessage(namespace, evt.TeamID, "received")

	switch evt.EventType {
	case "task_created":
		subject := evt.Detail["subject"]
		description := evt.Detail["description"]
		if subject == "" {
			logger.Info("task_created event missing subject")
			return
		}
		task, err := s.taskStore.Create(namespace, evt.TeamID, subject, description, nil)
		if err != nil {
			logger.Error(err, "failed to create task")
			return
		}
		logger.Info("task created via event", "taskID", task.TaskID)

	case "task_claimed":
		if evt.TaskID == "" {
			return
		}
		_, err := s.taskStore.Claim(namespace, evt.TeamID, evt.TaskID, evt.MemberName, 0)
		if err != nil {
			logger.Error(err, "failed to claim task", "taskID", evt.TaskID)
		}

	case "task_completed":
		if evt.TaskID == "" {
			return
		}
		result := evt.Detail["result"]
		task, err := s.taskStore.Complete(namespace, evt.TeamID, evt.TaskID, result)
		if err != nil {
			logger.Error(err, "failed to complete task", "taskID", evt.TaskID)
		} else {
			s.notifyLeadTaskSettled(namespace, evt.TeamID, task, result, "completed")
		}

	case "task_failed":
		if evt.TaskID == "" {
			return
		}
		reason := evt.Detail["reason"]
		if reason == "" {
			reason = evt.Detail["result"]
		}
		task, err := s.taskStore.Fail(namespace, evt.TeamID, evt.TaskID, reason)
		if err != nil {
			logger.Error(err, "failed to fail task", "taskID", evt.TaskID)
		} else {
			s.notifyLeadTaskSettled(namespace, evt.TeamID, task, reason, "failed")
		}

	case "member_joined", "member_idle", "member_working", "member_left":
		s.updateMemberPhase(ctx, namespace, evt)

	case "complete_team":
		s.handleCompleteTeam(ctx, namespace, evt)

	case "spawn_member":
		// Dynamic member spawn — handled by the controller via reconcile trigger.
		logger.Info("spawn_member event received, will trigger reconcile")

	default:
		logger.V(1).Info("unhandled team event type")
	}

	// After task events, update the team's task summary.
	switch evt.EventType {
	case "task_created", "task_claimed", "task_completed", "task_failed":
		s.updateTaskSummary(ctx, namespace, evt.TeamID)
	}
}

func (s *TeamEventSink) notifyLeadTaskSettled(namespace, teamName string, task *store.TeamTask, detail, verb string) {
	if s.router == nil || task == nil {
		return
	}
	owner := task.Owner
	if owner == "" {
		owner = "unknown"
	}
	if detail == "" {
		detail = "(no detail provided)"
	}
	label := "Result"
	if verb == "failed" {
		label = "Reason"
	}
	s.router.NotifyLead(namespace, teamName, task.Owner, fmt.Sprintf(
		"[team] Task %s (%s) %s by %s.\n\n%s:\n%s",
		task.TaskID, task.Subject, verb, owner, label, detail))
}

func (s *TeamEventSink) updateMemberPhase(ctx context.Context, namespace string, evt *TeamEventReport) {
	logger := log.FromContext(ctx)

	phaseMap := map[string]v1alpha1.MemberPhase{
		"member_joined":  v1alpha1.MemberPhaseWorking,
		"member_idle":    v1alpha1.MemberPhaseIdle,
		"member_working": v1alpha1.MemberPhaseWorking,
		"member_left":    v1alpha1.MemberPhaseLost,
	}
	phase, ok := phaseMap[evt.EventType]
	if !ok {
		return
	}

	err := retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var t v1alpha1.AgentTeam
		if err := s.client.Get(ctx, client.ObjectKey{Name: evt.TeamID, Namespace: namespace}, &t); err != nil {
			return err
		}
		for i, m := range t.Status.Members {
			if m.Name == evt.MemberName {
				t.Status.Members[i].Phase = phase
				return s.client.Status().Update(ctx, &t)
			}
		}
		return nil // member not found; nothing to update
	})
	if err != nil {
		logger.Error(err, "failed to update member phase", "member", evt.MemberName)
	}
}

func (s *TeamEventSink) updateTaskSummary(ctx context.Context, namespace, teamName string) {
	logger := log.FromContext(ctx)
	total, pending, inProgress, completed := s.taskStore.GetSummary(namespace, teamName)
	_ = total
	metrics.RecordTeamTasks(namespace, teamName, "pending", int(pending))
	metrics.RecordTeamTasks(namespace, teamName, "in_progress", int(inProgress))
	metrics.RecordTeamTasks(namespace, teamName, "completed", int(completed))

	err := retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var t v1alpha1.AgentTeam
		if err := s.client.Get(ctx, client.ObjectKey{Name: teamName, Namespace: namespace}, &t); err != nil {
			return err
		}
		t.Status.Tasks = &v1alpha1.TeamTaskSummary{
			Total:      total,
			Pending:    pending,
			InProgress: inProgress,
			Completed:  completed,
		}
		return s.client.Status().Update(ctx, &t)
	})
	if err != nil {
		logger.V(1).Info("failed to update task summary", "team", teamName, "error", err.Error())
	}
}

func (s *TeamEventSink) handleCompleteTeam(ctx context.Context, namespace string, evt *TeamEventReport) {
	logger := log.FromContext(ctx)

	var completed bool
	err := retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var t v1alpha1.AgentTeam
		if err := s.client.Get(ctx, client.ObjectKey{Name: evt.TeamID, Namespace: namespace}, &t); err != nil {
			return err
		}
		if t.Spec.Config == nil || t.Spec.Config.ShutdownPolicy != "lead-decides" {
			completed = false
			return nil
		}
		t.Status.Phase = v1alpha1.TeamPhaseCompleted
		if err := s.client.Status().Update(ctx, &t); err != nil {
			return err
		}
		completed = true
		return nil
	})
	if err != nil {
		logger.Error(err, "failed to mark team completed")
		return
	}
	if completed {
		var t v1alpha1.AgentTeam
		if getErr := s.client.Get(ctx, client.ObjectKey{Name: evt.TeamID, Namespace: namespace}, &t); getErr == nil {
			s.recorder.Eventf(&t, corev1.EventTypeNormal, "TeamCompleted", "team completed by lead decision")
		}
	}
}
