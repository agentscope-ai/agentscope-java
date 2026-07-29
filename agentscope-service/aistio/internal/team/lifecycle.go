package team

import (
	"context"
	"fmt"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// EXPERIMENTAL: distributed AgentTeam coordination is NOT wired into v0.1.
// Lifecycle/SessionSpawner are reference implementations exercised only when
// --enable-experimental is set and a future AgentTeamController adopts them.

// Lifecycle manages team creation, completion, timeout, and cleanup.
type Lifecycle struct {
	client    client.Client
	store     store.Store
	taskStore *TaskStore
	router    *MessageRouter
	spawner   *SessionSpawner
}

// NewLifecycle creates a new team Lifecycle manager.
func NewLifecycle(c client.Client, st store.Store, ts *TaskStore, mr *MessageRouter, ss *SessionSpawner) *Lifecycle {
	return &Lifecycle{
		client:    c,
		store:     st,
		taskStore: ts,
		router:    mr,
		spawner:   ss,
	}
}

// StartTeam initializes a team: creates sessions for lead and static members.
func (l *Lifecycle) StartTeam(ctx context.Context, team *v1alpha1.AgentTeam) error {
	logger := log.FromContext(ctx)
	logger.Info("starting team", "name", team.Name)

	// Spawn lead session
	leadSession, err := l.spawner.SpawnLeadSession(ctx, team)
	if err != nil {
		return fmt.Errorf("spawning lead session: %w", err)
	}

	team.Status.Phase = v1alpha1.TeamPhaseRunning
	team.Status.StartedAt = time.Now().Format(time.RFC3339)
	team.Status.Lead = &v1alpha1.TeamMemberStatus{
		Name:      "lead",
		AgentRef:  team.Spec.Lead.AgentRef.Name,
		SessionID: leadSession.SessionID,
		Phase:     v1alpha1.MemberPhaseWorking,
	}

	// Spawn static members
	team.Status.Members = make([]v1alpha1.TeamMemberStatus, 0, len(team.Spec.Members))
	for _, member := range team.Spec.Members {
		sess, err := l.spawner.SpawnMemberSession(ctx, team, member)
		memberStatus := v1alpha1.TeamMemberStatus{
			Name:     member.Name,
			Origin:   v1alpha1.MemberOriginStatic,
			AgentRef: member.AgentRef.Name,
			Phase:    v1alpha1.MemberPhaseJoining,
		}
		if err != nil {
			logger.Error(err, "failed to spawn member session", "member", member.Name)
			memberStatus.Phase = v1alpha1.MemberPhaseFailed
		} else {
			memberStatus.SessionID = sess.SessionID
			memberStatus.Phase = v1alpha1.MemberPhaseWorking
		}
		team.Status.Members = append(team.Status.Members, memberStatus)
	}

	team.Status.Tasks = &v1alpha1.TeamTaskSummary{}

	if err := l.client.Status().Update(ctx, team); err != nil {
		return err
	}

	activeCount := 0
	for _, m := range team.Status.Members {
		if m.Phase == v1alpha1.MemberPhaseWorking {
			activeCount++
		}
	}
	metrics.RecordTeamMembers(team.Namespace, team.Name, "working", activeCount)
	return nil
}

// CompleteTeam marks a team as completed and initiates cleanup.
func (l *Lifecycle) CompleteTeam(ctx context.Context, team *v1alpha1.AgentTeam) error {
	logger := log.FromContext(ctx)
	logger.Info("completing team", "name", team.Name)

	team.Status.Phase = v1alpha1.TeamPhaseCompleted

	// Terminate all member sessions
	l.terminateAllSessions(ctx, team)

	// Clean up routing
	l.router.DeleteTeam(team.Name, team.Namespace)

	return l.client.Status().Update(ctx, team)
}

// FailTeam marks a team as failed.
func (l *Lifecycle) FailTeam(ctx context.Context, team *v1alpha1.AgentTeam, reason string) error {
	team.Status.Phase = v1alpha1.TeamPhaseFailed

	cond := v1alpha1.Condition{
		Type:               v1alpha1.ConditionReady,
		Status:             metav1.ConditionFalse,
		LastTransitionTime: metav1.Now(),
		Reason:             "TeamFailed",
		Message:            reason,
	}
	setTeamCondition(&team.Status.Conditions, cond)

	l.terminateAllSessions(ctx, team)
	l.router.DeleteTeam(team.Name, team.Namespace)

	return l.client.Status().Update(ctx, team)
}

// CleanupTeamState removes a team's persistent task/message/session state
// from the store and clears in-memory routing. Called on finalize so cleanup
// is immediate rather than relying solely on retention purges.
func (l *Lifecycle) CleanupTeamState(ctx context.Context, team *v1alpha1.AgentTeam) {
	l.taskStore.DeleteTeam(team.Namespace, team.Name)
	l.router.DeleteTeam(team.Name, team.Namespace)
	if l.store != nil {
		_ = l.store.Sessions().DeleteByTeam(ctx, team.Name, team.Namespace)
	}
}

// CheckTimeout returns true if the team has exceeded its maxDuration.
func (l *Lifecycle) CheckTimeout(team *v1alpha1.AgentTeam) bool {
	if team.Spec.Lifecycle == nil || team.Spec.Lifecycle.MaxDuration == "" {
		return false
	}

	maxDur, err := time.ParseDuration(team.Spec.Lifecycle.MaxDuration)
	if err != nil {
		return false
	}

	startedAt, err := time.Parse(time.RFC3339, team.Status.StartedAt)
	if err != nil {
		return false
	}

	return time.Since(startedAt) > maxDur
}

// CheckAllComplete returns true if all tasks are completed (for all-complete shutdown policy).
func (l *Lifecycle) CheckAllComplete(team *v1alpha1.AgentTeam) bool {
	if team.Status.Tasks == nil {
		return false
	}
	return team.Status.Tasks.Total > 0 && team.Status.Tasks.Completed == team.Status.Tasks.Total
}

// ShouldCleanup checks if the team CRD should be garbage collected based on TTL.
func (l *Lifecycle) ShouldCleanup(team *v1alpha1.AgentTeam) bool {
	if team.Spec.Lifecycle == nil {
		return false
	}

	var ttlStr string
	switch team.Status.Phase {
	case v1alpha1.TeamPhaseCompleted:
		ttlStr = team.Spec.Lifecycle.TTLAfterCompleted
	case v1alpha1.TeamPhaseFailed:
		ttlStr = team.Spec.Lifecycle.TTLAfterFailed
	default:
		return false
	}

	if ttlStr == "" {
		return false
	}

	ttl, err := time.ParseDuration(ttlStr)
	if err != nil {
		return false
	}

	startedAt, err := time.Parse(time.RFC3339, team.Status.StartedAt)
	if err != nil {
		return false
	}

	return time.Since(startedAt) > ttl
}

// HandleMemberFailure processes a member pod failure and triggers recovery.
func (l *Lifecycle) HandleMemberFailure(ctx context.Context, team *v1alpha1.AgentTeam, memberName string, reason string) error {
	logger := log.FromContext(ctx)
	logger.Info("handling member failure", "team", team.Name, "member", memberName, "reason", reason)

	// Find the member in status
	for i, m := range team.Status.Members {
		if m.Name != memberName {
			continue
		}

		team.Status.Members[i].Phase = v1alpha1.MemberPhaseLost
		team.Status.Members[i].LastRestartReason = reason

		// Check recovery policy
		if team.Spec.Recovery == nil || team.Spec.Recovery.ReschedulePolicy == "None" {
			metrics.RecordTeamRecovery(team.Namespace, team.Name, "no_policy")
			return l.client.Status().Update(ctx, team)
		}

		// Check max restarts
		maxRestarts := int32(3)
		if team.Spec.Recovery.MaxRestarts > 0 {
			maxRestarts = team.Spec.Recovery.MaxRestarts
		}
		if m.RestartCount >= maxRestarts {
			team.Status.Members[i].Phase = v1alpha1.MemberPhaseFailed
			logger.Info("member exceeded max restarts", "member", memberName, "restarts", m.RestartCount)
			metrics.RecordTeamRecovery(team.Namespace, team.Name, "exhausted")
			return l.client.Status().Update(ctx, team)
		}

		// Auto reschedule
		if team.Spec.Recovery.ReschedulePolicy == "Auto" {
			metrics.RecordTeamRecovery(team.Namespace, team.Name, "attempted")
			return l.reschedMember(ctx, team, i, memberName)
		}

		return l.client.Status().Update(ctx, team)
	}

	return fmt.Errorf("member %s not found in team %s", memberName, team.Name)
}

// SpawnDynamicMember validates the request against team spec and spawns a member.
func (l *Lifecycle) SpawnDynamicMember(ctx context.Context, team *v1alpha1.AgentTeam, name, agentRef, prompt string) error {
	logger := log.FromContext(ctx)

	// Check dynamicMembers.enabled
	if team.Spec.DynamicMembers == nil || !team.Spec.DynamicMembers.Enabled {
		return fmt.Errorf("dynamic members not enabled for team %s", team.Name)
	}

	// Check maxTotal
	currentCount := len(team.Status.Members) + 1 // +1 for lead
	if team.Spec.DynamicMembers.MaxTotal > 0 && int32(currentCount) >= team.Spec.DynamicMembers.MaxTotal {
		return fmt.Errorf("team %s reached maxTotal %d", team.Name, team.Spec.DynamicMembers.MaxTotal)
	}

	// Check allowedAgentRefs
	if len(team.Spec.DynamicMembers.AllowedAgentRefs) > 0 {
		allowed := false
		for _, ref := range team.Spec.DynamicMembers.AllowedAgentRefs {
			if ref.Name == agentRef {
				allowed = true
				break
			}
		}
		if !allowed {
			return fmt.Errorf("agentRef %q not in allowedAgentRefs for team %s", agentRef, team.Name)
		}
	}

	// Create member spec and spawn
	member := v1alpha1.TeamMemberSpec{
		Name:     name,
		AgentRef: v1alpha1.ObjectReference{Name: agentRef},
		Prompt:   prompt,
	}
	sess, err := l.spawner.SpawnMemberSession(ctx, team, member)
	if err != nil {
		return fmt.Errorf("spawning dynamic member: %w", err)
	}

	// Add to team status
	team.Status.Members = append(team.Status.Members, v1alpha1.TeamMemberStatus{
		Name:      name,
		Origin:    v1alpha1.MemberOriginDynamic,
		AgentRef:  agentRef,
		SessionID: sess.SessionID,
		Phase:     v1alpha1.MemberPhaseWorking,
		AddedAt:   time.Now().Format(time.RFC3339),
	})

	logger.Info("dynamic member spawned", "team", team.Name, "member", name, "agent", agentRef)
	return l.client.Status().Update(ctx, team)
}

func (l *Lifecycle) reschedMember(ctx context.Context, team *v1alpha1.AgentTeam, idx int, memberName string) error {
	m := &team.Status.Members[idx]

	// Build recovery context from completed tasks
	recovery := &RecoveryContext{
		PreviousSessionID: m.SessionID,
		RestartCount:      m.RestartCount + 1,
	}

	// Gather completed tasks by this member from task store
	tasks := l.taskStore.List(team.Namespace, team.Name)
	for _, t := range tasks {
		if t.Owner == memberName && t.State == store.TaskStateCompleted {
			recovery.CompletedTasks = append(recovery.CompletedTasks, CompletedTask{
				ID:      t.TaskID,
				Subject: t.Subject,
				Result:  t.Result,
			})
		}
		if t.Owner == memberName && t.State == store.TaskStateInProgress {
			recovery.InterruptedTask = &InterruptedTask{
				ID:      t.TaskID,
				Subject: t.Subject,
				Note:    "Rolled back to pending due to member failure",
			}
			// Unclaim the interrupted task
			l.taskStore.Unclaim(team.Namespace, team.Name, t.TaskID)
		}
	}

	// Gather recent messages
	msgs := l.router.GetMessageHistory(team.Namespace, team.Name, 10)
	for _, msg := range msgs {
		if msg.ToMember == memberName || msg.FromMember == memberName {
			recovery.RecentMessages = append(recovery.RecentMessages, RecentMessage{
				From:      msg.FromMember,
				Content:   msg.Content,
				Timestamp: msg.CreatedAt.Format(time.RFC3339),
			})
		}
	}

	// Spawn recovery session
	sess, err := l.spawner.SpawnRecoverySession(ctx, team, memberName, m.AgentRef, recovery)
	if err != nil {
		metrics.RecordTeamRecovery(team.Namespace, team.Name, "failed")
		return fmt.Errorf("spawning recovery session: %w", err)
	}

	m.SessionID = sess.SessionID
	m.Phase = v1alpha1.MemberPhaseWorking
	m.RestartCount++
	m.LastRestartAt = time.Now().Format(time.RFC3339)

	metrics.RecordTeamRecovery(team.Namespace, team.Name, "success")
	return l.client.Status().Update(ctx, team)
}

// terminateAllSessions marks all of a team's sessions as terminated in the
// store. Best-effort: does not attempt live delivery of a terminate command
// to the data plane (see httpapi session terminate for that flow).
func (l *Lifecycle) terminateAllSessions(ctx context.Context, team *v1alpha1.AgentTeam) {
	logger := log.FromContext(ctx)
	if l.store == nil {
		return
	}

	sessions, err := l.store.Sessions().List(ctx, store.SessionFilter{
		Namespace: team.Namespace,
		TeamID:    team.Name,
	})
	if err != nil {
		logger.Error(err, "failed to list team sessions")
		return
	}

	for _, sess := range sessions {
		if err := l.store.Sessions().UpdatePhase(ctx, sess.ID, store.SessionPhaseTerminated); err != nil {
			logger.Error(err, "failed to terminate session", "session", sess.SessionID)
		}
	}
}

func setTeamCondition(conditions *[]v1alpha1.Condition, cond v1alpha1.Condition) {
	for i, c := range *conditions {
		if c.Type == cond.Type {
			(*conditions)[i] = cond
			return
		}
	}
	*conditions = append(*conditions, cond)
}
