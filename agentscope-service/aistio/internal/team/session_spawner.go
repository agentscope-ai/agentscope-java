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
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// TeamContext is injected into each teammate session at startup.
type TeamContext struct {
	TeamName         string           `json:"teamName"`
	Namespace        string           `json:"namespace,omitempty"`
	Objective        string           `json:"objective"`
	MyRole           string           `json:"myRole"`
	IsLead           bool             `json:"isLead"`
	Members          []MemberInfo     `json:"members"`
	AvailableActions []string         `json:"availableActions"`
	RecoveryContext  *RecoveryContext `json:"recoveryContext,omitempty"`
}

// MemberInfo describes a team member visible to all participants.
type MemberInfo struct {
	Name     string `json:"name"`
	AgentRef string `json:"agentRef"`
	Status   string `json:"status"`
}

// RecoveryContext provides context when a session is recovering from a crash.
type RecoveryContext struct {
	PreviousSessionID string           `json:"previousSessionId"`
	RestartCount      int32            `json:"restartCount"`
	CompletedTasks    []CompletedTask  `json:"completedTasks,omitempty"`
	InterruptedTask   *InterruptedTask `json:"interruptedTask,omitempty"`
	RecentMessages    []RecentMessage  `json:"recentMessages,omitempty"`
}

// CompletedTask records a task finished by the predecessor session.
type CompletedTask struct {
	ID      string `json:"id"`
	Subject string `json:"subject"`
	Result  string `json:"result"`
}

// InterruptedTask records a task that was in-progress when the session died.
type InterruptedTask struct {
	ID      string `json:"id"`
	Subject string `json:"subject"`
	Note    string `json:"note"`
}

// RecentMessage is a message from the team history injected for context.
type RecentMessage struct {
	From      string `json:"from"`
	Content   string `json:"content"`
	Timestamp string `json:"timestamp"`
}

// SessionSpawner registers store-backed sessions for team members with
// injected team context. Activation of the remote data plane is handled by
// the Activator (see activator.go); this layer only allocates session rows.
type SessionSpawner struct {
	store store.Store
}

// NewSessionSpawner creates a new SessionSpawner.
func NewSessionSpawner(st store.Store) *SessionSpawner {
	return &SessionSpawner{store: st}
}

// SpawnMemberSession registers a BYO member's session with a new UUID and team context.
func (s *SessionSpawner) SpawnMemberSession(
	ctx context.Context,
	team *store.Team,
	member *store.TeamMember,
	roster []*store.TeamMember,
	recovery *RecoveryContext,
) (*store.Session, error) {
	isLead := member.MemberName == "lead"
	teamCtx := s.buildTeamContext(team, member.MemberName, isLead, roster, recovery)
	return s.CreateMemberSession(ctx, team, member.AgentRef, member.MemberName, teamCtx, "")
}

// CreateMemberSession upserts a store session with TeamContext.
// When sessionID is empty a new UUID is allocated (BYO); Managed callers pass the
// product find-or-create id so resolve can look up the same row by session_id.
func (s *SessionSpawner) CreateMemberSession(
	ctx context.Context,
	team *store.Team,
	agentRef, memberName string,
	teamCtx *TeamContext,
	sessionID string,
) (*store.Session, error) {
	return s.createSession(ctx, team, agentRef, memberName, teamCtx, sessionID)
}

func (s *SessionSpawner) buildTeamContext(
	team *store.Team,
	myRole string,
	isLead bool,
	roster []*store.TeamMember,
	recovery *RecoveryContext,
) *TeamContext {
	members := make([]MemberInfo, 0, len(roster))
	for _, m := range roster {
		status := m.Phase
		if status == "" {
			status = store.MemberPhaseJoining
		}
		members = append(members, MemberInfo{
			Name:     m.MemberName,
			AgentRef: m.AgentRef,
			Status:   status,
		})
	}

	actions := []string{
		"listTasks", "listClaimableTasks", "claimTask", "unclaimTask",
		"completeTask", "failTask",
		"sendMessage", "broadcastMessage", "listMessages", "listMembers", "submitPlan",
	}
	if isLead {
		actions = append(actions,
			"createTask", "assignTask", "spawnMember", "shutdownMember",
			"approvePlan", "rejectPlan", "completeTeam",
		)
	}

	return &TeamContext{
		TeamName:         team.Name,
		Namespace:        team.Namespace,
		Objective:        team.Objective,
		MyRole:           myRole,
		IsLead:           isLead,
		Members:          members,
		AvailableActions: actions,
		RecoveryContext:  recovery,
	}
}

func (s *SessionSpawner) createSession(
	ctx context.Context,
	team *store.Team,
	agentRef, memberName string,
	teamCtx *TeamContext,
	sessionID string,
) (*store.Session, error) {
	contextJSON, err := json.Marshal(teamCtx)
	if err != nil {
		return nil, fmt.Errorf("marshaling team context: %w", err)
	}
	if sessionID == "" {
		sessionID = uuid.NewString()
	}

	now := time.Now().UTC()
	sess := &store.Session{
		SessionID:    sessionID,
		AgentName:    agentRef,
		Namespace:    team.Namespace,
		Phase:        store.SessionPhaseActive,
		TeamID:       team.Name,
		TeamRole:     memberName,
		TeamContext:  contextJSON,
		StartedAt:    &now,
		LastActiveAt: &now,
	}

	saved, err := s.store.Sessions().Upsert(ctx, sess)
	if err != nil {
		return nil, fmt.Errorf("creating session for %s: %w", memberName, err)
	}
	return saved, nil
}
