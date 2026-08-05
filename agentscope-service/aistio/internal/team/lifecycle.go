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

package team

import (
	"context"
	"fmt"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// Lifecycle manages team creation, completion, timeout, and cleanup against
// the store-backed Team repository. Optional CRD projection is handled by the
// controller via convert.go — this layer never imports the CRD client.
type Lifecycle struct {
	store     store.Store
	taskStore *TaskStore
	router    *MessageRouter
	spawner   *SessionSpawner
	activator *Activator
}

// NewLifecycle creates a store-backed team Lifecycle manager.
func NewLifecycle(st store.Store, ts *TaskStore, mr *MessageRouter, ss *SessionSpawner) *Lifecycle {
	return &Lifecycle{
		store:     st,
		taskStore: ts,
		router:    mr,
		spawner:   ss,
	}
}

// SetActivator wires optional data-plane wakeup after session allocation.
func (l *Lifecycle) SetActivator(a *Activator) {
	l.activator = a
}

// StartTeam initializes a team: creates sessions for lead and static members.
func (l *Lifecycle) StartTeam(ctx context.Context, team *store.Team) error {
	logger := log.FromContext(ctx)
	logger.Info("starting team", "name", team.Name)

	members, err := l.store.Teams().ListMembers(ctx, team.Namespace, team.Name)
	if err != nil {
		return fmt.Errorf("listing members: %w", err)
	}
	if len(members) == 0 {
		return fmt.Errorf("team %s has no members", team.Name)
	}

	for _, member := range members {
		teamCtx := l.spawner.buildTeamContext(team, member.MemberName, member.MemberName == "lead", members, nil)
		sess, err := l.startMember(ctx, team, member, members, teamCtx)
		if err != nil {
			logger.Error(err, "failed to start member", "member", member.MemberName)
			_ = l.store.Teams().UpdateMemberPhase(ctx, team.Namespace, team.Name, member.MemberName, store.MemberPhaseFailed)
			continue
		}
		_ = l.store.Teams().UpdateMemberPhase(ctx, team.Namespace, team.Name, member.MemberName, store.MemberPhaseWorking)
		l.router.RegisterMember(team.Name, &MemberLocation{
			MemberName: member.MemberName,
			AgentName:  member.AgentRef,
			SessionID:  sess.SessionID,
			Connected:  true,
		})
	}

	if err := l.store.Teams().UpdatePhase(ctx, team.Namespace, team.Name, store.TeamPhaseRunning); err != nil {
		return err
	}
	team.Phase = store.TeamPhaseRunning

	activeCount := 0
	fresh, _ := l.store.Teams().ListMembers(ctx, team.Namespace, team.Name)
	for _, m := range fresh {
		if m.Phase == store.MemberPhaseWorking {
			activeCount++
		}
	}
	metrics.RecordTeamMembers(team.Namespace, team.Name, "working", activeCount)
	return nil
}

// startMember allocates a session and activates the data plane for one member.
// Managed: find-or-create + store bind + wake event (Activator.ActivateManaged).
// BYO: UUID store session + team_join.
func (l *Lifecycle) startMember(
	ctx context.Context,
	team *store.Team,
	member *store.TeamMember,
	roster []*store.TeamMember,
	teamCtx *TeamContext,
) (*store.Session, error) {
	logger := log.FromContext(ctx)

	if member.DeployMode == store.MemberDeployManaged {
		if l.activator == nil {
			return nil, fmt.Errorf("managed activator not configured")
		}
		return l.activator.ActivateManaged(ctx, team, member, teamCtx)
	}

	sess, err := l.spawner.SpawnMemberSession(ctx, team, member, roster, nil)
	if err != nil {
		return nil, err
	}
	_ = l.store.Teams().BindMemberSession(ctx, team.Namespace, team.Name, member.MemberName,
		sess.SessionID, member.ManagedSessionID, "")
	member.SessionID = sess.SessionID
	if l.activator != nil {
		if err := l.activator.ActivateMember(ctx, team, member, teamCtx); err != nil {
			logger.Error(err, "failed to activate BYO member", "member", member.MemberName)
			return sess, err
		}
	}
	return sess, nil
}

// CompleteTeam marks a team as completed and initiates cleanup.
func (l *Lifecycle) CompleteTeam(ctx context.Context, team *store.Team) error {
	logger := log.FromContext(ctx)
	logger.Info("completing team", "name", team.Name)

	if err := l.store.Teams().UpdatePhase(ctx, team.Namespace, team.Name, store.TeamPhaseCompleted); err != nil {
		return err
	}
	team.Phase = store.TeamPhaseCompleted
	l.terminateAllSessions(ctx, team)
	l.router.DeleteTeam(team.Name, team.Namespace)
	return nil
}

// FailTeam marks a team as failed.
func (l *Lifecycle) FailTeam(ctx context.Context, team *store.Team, reason string) error {
	logger := log.FromContext(ctx)
	logger.Info("failing team", "name", team.Name, "reason", reason)

	if err := l.store.Teams().UpdatePhase(ctx, team.Namespace, team.Name, store.TeamPhaseFailed); err != nil {
		return err
	}
	team.Phase = store.TeamPhaseFailed
	l.terminateAllSessions(ctx, team)
	l.router.DeleteTeam(team.Name, team.Namespace)
	return nil
}

// CleanupTeamState removes a team's persistent task/message/session/member state.
func (l *Lifecycle) CleanupTeamState(ctx context.Context, team *store.Team) {
	l.releaseManagedSessions(ctx, team)
	l.taskStore.DeleteTeam(team.Namespace, team.Name)
	l.router.DeleteTeam(team.Name, team.Namespace)
	if l.store != nil {
		_ = l.store.Sessions().DeleteByTeam(ctx, team.Name, team.Namespace)
		_ = l.store.Teams().Delete(ctx, team.Namespace, team.Name)
	}
}

// releaseManagedSessions deletes the product sessions the team allocated for its
// managed members. Must run before the member rows are deleted, since they hold
// the session ids. Failures are logged only: teardown must still finish.
func (l *Lifecycle) releaseManagedSessions(ctx context.Context, team *store.Team) {
	if l.activator == nil || l.store == nil {
		return
	}
	logger := log.FromContext(ctx)
	members, err := l.store.Teams().ListMembers(ctx, team.Namespace, team.Name)
	if err != nil {
		logger.Error(err, "failed to list members for session release", "team", team.Name)
		return
	}
	for _, m := range members {
		if err := l.activator.ReleaseManagedMemberSession(ctx, m); err != nil {
			logger.Error(err, "failed to release managed member session",
				"team", team.Name, "member", m.MemberName)
		}
	}
}

// CheckTimeout returns true if the team has exceeded its maxDuration.
func (l *Lifecycle) CheckTimeout(team *store.Team) bool {
	extra := ParseSpecExtra(team)
	if extra.Lifecycle == nil || extra.Lifecycle.MaxDuration == "" || team.StartedAt == nil {
		return false
	}
	maxDur, err := time.ParseDuration(extra.Lifecycle.MaxDuration)
	if err != nil {
		return false
	}
	return time.Since(*team.StartedAt) > maxDur
}

// CheckAllComplete returns true when every task reached a terminal state
// (completed or failed), so a board with failures still settles.
func (l *Lifecycle) CheckAllComplete(team *store.Team) bool {
	tasks := l.taskStore.List(team.Namespace, team.Name)
	if len(tasks) == 0 {
		return false
	}
	for _, t := range tasks {
		if !store.IsTaskTerminal(t.State) {
			return false
		}
	}
	return true
}

// ShouldCleanup checks if the team should be garbage collected based on TTL.
func (l *Lifecycle) ShouldCleanup(team *store.Team) bool {
	extra := ParseSpecExtra(team)
	if extra.Lifecycle == nil || team.StartedAt == nil {
		return false
	}
	var ttlStr string
	switch team.Phase {
	case store.TeamPhaseCompleted:
		ttlStr = extra.Lifecycle.TTLAfterCompleted
	case store.TeamPhaseFailed:
		ttlStr = extra.Lifecycle.TTLAfterFailed
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
	return time.Since(*team.StartedAt) > ttl
}

// HandleMemberFailure processes a member failure and triggers recovery.
func (l *Lifecycle) HandleMemberFailure(ctx context.Context, team *store.Team, memberName, reason string) error {
	logger := log.FromContext(ctx)
	logger.Info("handling member failure", "team", team.Name, "member", memberName, "reason", reason)

	m, err := l.store.Teams().GetMember(ctx, team.Namespace, team.Name, memberName)
	if err != nil {
		return fmt.Errorf("member %s not found in team %s: %w", memberName, team.Name, err)
	}

	_ = l.store.Teams().UpdateMemberPhase(ctx, team.Namespace, team.Name, memberName, store.MemberPhaseLost)
	m.Phase = store.MemberPhaseLost
	m.LastRestartReason = reason

	extra := ParseSpecExtra(team)
	if extra.Recovery == nil || extra.Recovery.ReschedulePolicy == "None" {
		metrics.RecordTeamRecovery(team.Namespace, team.Name, "no_policy")
		l.router.NotifyLead(team.Namespace, team.Name, memberName, fmt.Sprintf(
			"[team] Member %s was lost (%s) and will not be restarted (reschedulePolicy=None)."+
				" Reassign its work or complete without it.", memberName, reason))
		return nil
	}

	maxRestarts := int32(3)
	if extra.Recovery.MaxRestarts > 0 {
		maxRestarts = extra.Recovery.MaxRestarts
	}
	if m.RestartCount >= maxRestarts {
		_ = l.store.Teams().UpdateMemberPhase(ctx, team.Namespace, team.Name, memberName, store.MemberPhaseFailed)
		metrics.RecordTeamRecovery(team.Namespace, team.Name, "exhausted")
		l.router.NotifyLead(team.Namespace, team.Name, memberName, fmt.Sprintf(
			"[team] Member %s failed permanently after %d restarts (%s)."+
				" Reassign its work or complete without it.", memberName, m.RestartCount, reason))
		return nil
	}

	if extra.Recovery.ReschedulePolicy == "Auto" {
		metrics.RecordTeamRecovery(team.Namespace, team.Name, "attempted")
		return l.rescheduleMember(ctx, team, m)
	}
	return nil
}

// SpawnDynamicMember validates the request against team policy and spawns a member.
func (l *Lifecycle) SpawnDynamicMember(ctx context.Context, team *store.Team, name, agentRef, prompt string) error {
	logger := log.FromContext(ctx)
	extra := ParseSpecExtra(team)
	if extra.DynamicMembers == nil || !extra.DynamicMembers.Enabled {
		return fmt.Errorf("dynamic members not enabled for team %s", team.Name)
	}

	members, err := l.store.Teams().ListMembers(ctx, team.Namespace, team.Name)
	if err != nil {
		return err
	}
	if extra.DynamicMembers.MaxTotal > 0 && int32(len(members)) >= extra.DynamicMembers.MaxTotal {
		return fmt.Errorf("team %s reached maxTotal %d", team.Name, extra.DynamicMembers.MaxTotal)
	}
	if len(extra.DynamicMembers.AllowedAgentRefs) > 0 {
		allowed := false
		for _, ref := range extra.DynamicMembers.AllowedAgentRefs {
			if ref.Name == agentRef {
				allowed = true
				break
			}
		}
		if !allowed {
			return fmt.Errorf("agentRef %q not in allowedAgentRefs for team %s", agentRef, team.Name)
		}
	}

	member, err := l.store.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName:   team.Name,
		Namespace:  team.Namespace,
		MemberName: name,
		AgentRef:   agentRef,
		Prompt:     prompt,
		Origin:     store.MemberOriginDynamic,
		DeployMode: store.MemberDeployBYO,
		Phase:      store.MemberPhaseJoining,
	})
	if err != nil {
		return err
	}

	roster, _ := l.store.Teams().ListMembers(ctx, team.Namespace, team.Name)
	teamCtx := l.spawner.buildTeamContext(team, name, false, roster, nil)
	sess, err := l.startMember(ctx, team, member, roster, teamCtx)
	if err != nil {
		return fmt.Errorf("spawning dynamic member: %w", err)
	}
	_ = l.store.Teams().UpdateMemberPhase(ctx, team.Namespace, team.Name, name, store.MemberPhaseWorking)
	l.router.RegisterMember(team.Name, &MemberLocation{
		MemberName: name,
		AgentName:  agentRef,
		SessionID:  sess.SessionID,
		Connected:  true,
	})

	logger.Info("dynamic member spawned", "team", team.Name, "member", name, "agent", agentRef)
	return nil
}

// ShutdownMember retires one member: asks its runtime to leave the team, flips
// the member phase to Shutdown, drops it from the router, and terminates the
// member's store session. Data-plane deactivation is best-effort — a member
// whose instance is already gone still gets fully shut down.
func (l *Lifecycle) ShutdownMember(ctx context.Context, team *store.Team, memberName string) error {
	logger := log.FromContext(ctx)
	if team == nil || memberName == "" {
		return fmt.Errorf("team and memberName required")
	}

	m, err := l.store.Teams().GetMember(ctx, team.Namespace, team.Name, memberName)
	if err != nil {
		return err
	}

	if l.activator != nil {
		if err := l.activator.DeactivateMember(ctx, team, m); err != nil {
			logger.Info("team_leave failed, continuing shutdown",
				"team", team.Name, "member", memberName, "err", err)
		}
	}

	if err := l.store.Teams().UpdateMemberPhase(ctx, team.Namespace, team.Name, memberName, store.MemberPhaseShutdown); err != nil {
		return err
	}
	l.router.UnregisterMember(team.Name, memberName)
	l.terminateMemberSessions(ctx, team, memberName)

	logger.Info("member shut down", "team", team.Name, "member", memberName)
	return nil
}

func (l *Lifecycle) terminateMemberSessions(ctx context.Context, team *store.Team, memberName string) {
	logger := log.FromContext(ctx)
	if l.store == nil {
		return
	}
	sessions, err := l.store.Sessions().List(ctx, store.SessionFilter{
		Namespace: team.Namespace,
		TeamID:    team.Name,
		TeamRole:  memberName,
	})
	if err != nil {
		logger.Error(err, "failed to list member sessions", "member", memberName)
		return
	}
	for _, sess := range sessions {
		if err := l.store.Sessions().UpdatePhase(ctx, sess.ID, store.SessionPhaseTerminated); err != nil {
			logger.Error(err, "failed to terminate member session", "session", sess.SessionID)
		}
	}
}

func (l *Lifecycle) rescheduleMember(ctx context.Context, team *store.Team, m *store.TeamMember) error {
	recovery := &RecoveryContext{
		PreviousSessionID: m.SessionID,
		RestartCount:      m.RestartCount + 1,
	}

	tasks := l.taskStore.List(team.Namespace, team.Name)
	for _, t := range tasks {
		if t.Owner == m.MemberName && t.State == store.TaskStateCompleted {
			recovery.CompletedTasks = append(recovery.CompletedTasks, CompletedTask{
				ID: t.TaskID, Subject: t.Subject, Result: t.Result,
			})
		}
		if t.Owner == m.MemberName && t.State == store.TaskStateInProgress {
			recovery.InterruptedTask = &InterruptedTask{
				ID: t.TaskID, Subject: t.Subject,
				Note: "Rolled back to pending due to member failure",
			}
			l.taskStore.Unclaim(team.Namespace, team.Name, t.TaskID)
			l.router.NotifyLead(team.Namespace, team.Name, m.MemberName, fmt.Sprintf(
				"[team] Task %s (%s) was rolled back to pending: member %s failed mid-task"+
					" and is being restarted.", t.TaskID, t.Subject, m.MemberName))
		}
	}

	msgs := l.router.GetMessageHistory(team.Namespace, team.Name, 10)
	for _, msg := range msgs {
		if msg.ToMember == m.MemberName || msg.FromMember == m.MemberName {
			recovery.RecentMessages = append(recovery.RecentMessages, RecentMessage{
				From: msg.FromMember, Content: msg.Content,
				Timestamp: msg.CreatedAt.Format(time.RFC3339),
			})
		}
	}

	roster, _ := l.store.Teams().ListMembers(ctx, team.Namespace, team.Name)
	teamCtx := l.spawner.buildTeamContext(team, m.MemberName, m.MemberName == "lead", roster, recovery)

	var sess *store.Session
	var err error
	if m.DeployMode == store.MemberDeployManaged {
		if l.activator == nil {
			metrics.RecordTeamRecovery(team.Namespace, team.Name, "failed")
			return fmt.Errorf("managed activator not configured")
		}
		sess, err = l.activator.ActivateManaged(ctx, team, m, teamCtx)
	} else {
		sess, err = l.spawner.SpawnMemberSession(ctx, team, m, roster, recovery)
		if err == nil {
			_ = l.store.Teams().BindMemberSession(ctx, team.Namespace, team.Name, m.MemberName, sess.SessionID, "", "")
			m.SessionID = sess.SessionID
			if l.activator != nil {
				_ = l.activator.ActivateMember(ctx, team, m, teamCtx)
			}
		}
	}
	if err != nil {
		metrics.RecordTeamRecovery(team.Namespace, team.Name, "failed")
		return fmt.Errorf("spawning recovery session: %w", err)
	}

	now := time.Now().UTC()
	m.SessionID = sess.SessionID
	if m.DeployMode == store.MemberDeployManaged {
		m.ManagedSessionID = sess.SessionID
	}
	m.Phase = store.MemberPhaseWorking
	m.RestartCount++
	m.LastRestartAt = &now
	_, err = l.store.Teams().UpsertMember(ctx, m)
	if err != nil {
		metrics.RecordTeamRecovery(team.Namespace, team.Name, "failed")
		return err
	}
	metrics.RecordTeamRecovery(team.Namespace, team.Name, "success")
	return nil
}

func (l *Lifecycle) terminateAllSessions(ctx context.Context, team *store.Team) {
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
