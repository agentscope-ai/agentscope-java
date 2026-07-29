package controller

import (
	"context"
	"encoding/json"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/client-go/tools/record"
	"k8s.io/client-go/util/retry"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
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
}

// NewTeamEventSink creates a new TeamEventSink.
func NewTeamEventSink(c client.Client, ts *team.TaskStore, rec record.EventRecorder) *TeamEventSink {
	return &TeamEventSink{
		client:    c,
		taskStore: ts,
		recorder:  rec,
	}
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
		_, err := s.taskStore.Complete(namespace, evt.TeamID, evt.TaskID, result)
		if err != nil {
			logger.Error(err, "failed to complete task", "taskID", evt.TaskID)
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
	if evt.EventType == "task_created" || evt.EventType == "task_claimed" || evt.EventType == "task_completed" {
		s.updateTaskSummary(ctx, namespace, evt.TeamID)
	}
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
