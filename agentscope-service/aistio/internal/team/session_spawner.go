package team

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// TeamContext is injected into each teammate session at startup.
type TeamContext struct {
	TeamName         string           `json:"teamName"`
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
// injected team context. The actual runtime process is expected to look up
// its assigned SessionID (e.g. via an injected env var or the framework's
// own bootstrap flow) and report activity back through the same SessionID.
type SessionSpawner struct {
	store store.Store
}

// NewSessionSpawner creates a new SessionSpawner.
func NewSessionSpawner(st store.Store) *SessionSpawner {
	return &SessionSpawner{store: st}
}

// SpawnLeadSession registers the lead's session with team context.
func (s *SessionSpawner) SpawnLeadSession(ctx context.Context, team *v1alpha1.AgentTeam) (*store.Session, error) {
	teamCtx := s.buildTeamContext(team, "lead", true, nil)
	return s.createSession(ctx, team, team.Spec.Lead.AgentRef.Name, "lead", teamCtx)
}

// SpawnMemberSession registers a member's session with team context.
func (s *SessionSpawner) SpawnMemberSession(ctx context.Context, team *v1alpha1.AgentTeam, member v1alpha1.TeamMemberSpec) (*store.Session, error) {
	teamCtx := s.buildTeamContext(team, member.Name, false, nil)
	return s.createSession(ctx, team, member.AgentRef.Name, member.Name, teamCtx)
}

// SpawnRecoverySession registers a replacement session with recovery context.
func (s *SessionSpawner) SpawnRecoverySession(
	ctx context.Context,
	team *v1alpha1.AgentTeam,
	memberName, agentRef string,
	recovery *RecoveryContext,
) (*store.Session, error) {
	teamCtx := s.buildTeamContext(team, memberName, false, recovery)
	return s.createSession(ctx, team, agentRef, memberName, teamCtx)
}

func (s *SessionSpawner) buildTeamContext(
	team *v1alpha1.AgentTeam,
	myRole string,
	isLead bool,
	recovery *RecoveryContext,
) *TeamContext {
	members := make([]MemberInfo, 0)

	// Add lead
	members = append(members, MemberInfo{
		Name:     "lead",
		AgentRef: team.Spec.Lead.AgentRef.Name,
		Status:   "working",
	})

	// Add static members
	for _, m := range team.Spec.Members {
		status := "joining"
		if team.Status.Members != nil {
			for _, ms := range team.Status.Members {
				if ms.Name == m.Name {
					status = string(ms.Phase)
					break
				}
			}
		}
		members = append(members, MemberInfo{
			Name:     m.Name,
			AgentRef: m.AgentRef.Name,
			Status:   status,
		})
	}

	actions := []string{
		"listTasks", "claimTask", "completeTask",
		"sendMessage", "broadcastMessage", "listMembers",
	}
	if isLead {
		actions = append(actions,
			"createTask", "spawnMember", "shutdownMember",
			"approvePlan", "rejectPlan", "completeTeam",
		)
	}

	return &TeamContext{
		TeamName:         team.Name,
		Objective:        team.Spec.Objective,
		MyRole:           myRole,
		IsLead:           isLead,
		Members:          members,
		AvailableActions: actions,
		RecoveryContext:  recovery,
	}
}

func (s *SessionSpawner) createSession(
	ctx context.Context,
	team *v1alpha1.AgentTeam,
	agentRef, memberName string,
	teamCtx *TeamContext,
) (*store.Session, error) {
	contextJSON, err := json.Marshal(teamCtx)
	if err != nil {
		return nil, fmt.Errorf("marshaling team context: %w", err)
	}

	now := time.Now().UTC()
	sess := &store.Session{
		SessionID:    uuid.NewString(),
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
