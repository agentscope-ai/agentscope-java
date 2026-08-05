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
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const (
	// CommandTeamJoin asks a data-plane instance to adopt a team session.
	CommandTeamJoin = "team_join"
	// CommandTeamLeave asks a data-plane instance to leave a team session.
	CommandTeamLeave = "team_leave"

	capabilityTeamCoordination = "team-coordination"
)

// SessionCommander delivers SessionCommand over ASDP (optional).
type SessionCommander interface {
	SendSessionCommandWithParams(namespace, instanceID, sessionID, command string, params []byte) error
}

// ErrMemberBusy reports that a member could not be woken because a turn of its
// own is already running. It is backpressure rather than a delivery failure: the
// notice must stay queued until the member is idle, or a teammate's report is
// lost precisely when the member is at its busiest.
var ErrMemberBusy = errors.New("team member is running a turn")

// ManagedSessionAPI is the product-plane surface used to allocate and wake
// Managed member sessions (find-or-create + data-plane events).
type ManagedSessionAPI interface {
	FindOrCreateSessionID(ctx context.Context, ownerID, agentID, environmentID, externalKey string) (sessionID string, err error)
	PostSessionWakeEvent(ctx context.Context, sessionID, ownerID, text string) error
	DeleteManagedSession(ctx context.Context, ownerID, sessionID string) error
}

// Activator wakes data-plane members after store sessions are allocated.
type Activator struct {
	store     store.Store
	registry  *dataplane.Registry
	commander SessionCommander
	managed   ManagedSessionAPI
	spawner   *SessionSpawner
	http      *http.Client
}

// NewActivator creates a team member activator.
func NewActivator(st store.Store, registry *dataplane.Registry, commander SessionCommander) *Activator {
	return &Activator{
		store:     st,
		registry:  registry,
		commander: commander,
		spawner:   NewSessionSpawner(st),
		http:      &http.Client{Timeout: 15 * time.Second},
	}
}

// SetManagedSessionAPI wires product find-or-create + wake for Managed members.
func (a *Activator) SetManagedSessionAPI(api ManagedSessionAPI) {
	if a != nil {
		a.managed = api
	}
}

// ActivateManaged allocates a product session (find-or-create), writes the
// runtime store.Session row with TeamContext under that id, binds the member,
// and posts a wake event to the data plane.
func (a *Activator) ActivateManaged(
	ctx context.Context,
	team *store.Team,
	member *store.TeamMember,
	teamCtx *TeamContext,
) (*store.Session, error) {
	logger := log.FromContext(ctx)
	if a.managed == nil {
		return nil, fmt.Errorf("managed session API not configured")
	}
	if member.OwnerID == "" || member.ManagedAgentID == "" {
		return nil, fmt.Errorf("managed member %s requires ownerId and managedAgentId", member.MemberName)
	}

	externalKey := fmt.Sprintf("team|%s/%s|%s", team.Namespace, team.Name, member.MemberName)
	sessionID, err := a.managed.FindOrCreateSessionID(ctx, member.OwnerID, member.ManagedAgentID, "", externalKey)
	if err != nil {
		return nil, fmt.Errorf("find-or-create: %w", err)
	}

	sess, err := a.spawner.CreateMemberSession(ctx, team, member.AgentRef, member.MemberName, teamCtx, sessionID)
	if err != nil {
		return nil, err
	}

	_ = a.store.Teams().BindMemberSession(ctx, team.Namespace, team.Name, member.MemberName,
		sessionID, sessionID, "")

	wake := buildManagedWakeText(team, member, teamCtx)
	if err := a.managed.PostSessionWakeEvent(ctx, sessionID, member.OwnerID, wake); err != nil {
		logger.Error(err, "managed wake event failed",
			"team", team.Name, "member", member.MemberName, "session", sessionID)
		// Session is bound; caller can retry wake. Surface the error so member recovery can engage.
		return sess, fmt.Errorf("wake event: %w", err)
	}
	logger.Info("managed member activated",
		"team", team.Name, "member", member.MemberName, "session", sessionID)
	return sess, nil
}

// ActivateMember resolves the member's instance and delivers team_join (BYO path).
// Managed members must use ActivateManaged.
func (a *Activator) ActivateMember(ctx context.Context, team *store.Team, member *store.TeamMember, teamCtx *TeamContext) error {
	if member.DeployMode == store.MemberDeployManaged {
		_, err := a.ActivateManaged(ctx, team, member, teamCtx)
		return err
	}
	payload, err := json.Marshal(teamCtx)
	if err != nil {
		return err
	}
	return a.activateBYO(ctx, team, member, payload)
}

func (a *Activator) activateBYO(ctx context.Context, team *store.Team, member *store.TeamMember, payload []byte) error {
	logger := log.FromContext(ctx)
	if member.SessionID == "" {
		return fmt.Errorf("member %s has no sessionId", member.MemberName)
	}
	entry, err := a.pickInstance(team.Namespace, member.AgentRef)
	if err != nil {
		return err
	}

	_ = a.store.Teams().BindMemberSession(ctx, team.Namespace, team.Name, member.MemberName,
		member.SessionID, member.ManagedSessionID, entry.InstanceID)

	if a.commander != nil {
		if err := a.commander.SendSessionCommandWithParams(
			team.Namespace, entry.InstanceID, member.SessionID, CommandTeamJoin, payload,
		); err == nil {
			logger.Info("team_join delivered via ASDP",
				"team", team.Name, "member", member.MemberName, "instance", entry.InstanceID)
			return nil
		} else {
			logger.Info("ASDP team_join failed, trying HTTP fallback",
				"team", team.Name, "member", member.MemberName, "err", err)
		}
	}

	if entry.BaseURL == "" {
		return fmt.Errorf("no ASDP connection and empty baseUrl for instance %s", entry.InstanceID)
	}
	return a.httpJoin(ctx, entry.BaseURL, member.SessionID, payload)
}

// DeactivateMember tells the member's runtime to leave the team. BYO members
// receive team_leave over ASDP (HTTP fallback); for Managed members the store
// session terminate performed by Lifecycle.ShutdownMember is sufficient, so
// this is a no-op that never fails the shutdown path.
// ReleaseManagedMemberSession deletes the product session that was allocated for
// a managed member. Called during team teardown, where the member row is about
// to be deleted and is the only record of which session the team allocated.
func (a *Activator) ReleaseManagedMemberSession(ctx context.Context, member *store.TeamMember) error {
	if a == nil || a.managed == nil || member == nil {
		return nil
	}
	if member.DeployMode != store.MemberDeployManaged || member.OwnerID == "" {
		return nil
	}
	sessionID := member.ManagedSessionID
	if sessionID == "" {
		sessionID = member.SessionID
	}
	if sessionID == "" {
		return nil
	}
	return a.managed.DeleteManagedSession(ctx, member.OwnerID, sessionID)
}

func (a *Activator) DeactivateMember(ctx context.Context, team *store.Team, member *store.TeamMember) error {
	if a == nil || team == nil || member == nil {
		return nil
	}
	if member.DeployMode == store.MemberDeployManaged {
		return nil
	}
	if member.SessionID == "" {
		return fmt.Errorf("member %s has no sessionId", member.MemberName)
	}

	payload, err := json.Marshal(map[string]string{
		"teamName":   team.Name,
		"namespace":  team.Namespace,
		"memberName": member.MemberName,
	})
	if err != nil {
		return err
	}

	logger := log.FromContext(ctx)
	entry, err := a.pickInstance(team.Namespace, member.AgentRef)
	if err != nil {
		return err
	}
	if a.commander != nil {
		if err := a.commander.SendSessionCommandWithParams(
			team.Namespace, entry.InstanceID, member.SessionID, CommandTeamLeave, payload,
		); err == nil {
			logger.Info("team_leave delivered via ASDP",
				"team", team.Name, "member", member.MemberName, "instance", entry.InstanceID)
			return nil
		} else {
			logger.Info("ASDP team_leave failed, trying HTTP fallback",
				"team", team.Name, "member", member.MemberName, "err", err)
		}
	}
	if entry.BaseURL == "" {
		return fmt.Errorf("no ASDP connection and empty baseUrl for instance %s", entry.InstanceID)
	}
	return a.httpLeave(ctx, entry.BaseURL, member.SessionID, payload)
}

func (a *Activator) pickInstance(namespace, agentName string) (*dataplane.Entry, error) {
	if a.registry == nil {
		return nil, fmt.Errorf("dataplane registry not configured")
	}
	entries := a.registry.ListByAgent(agentName, namespace)
	var capable, healthy []*dataplane.Entry
	for _, e := range entries {
		if !e.Healthy {
			continue
		}
		healthy = append(healthy, e)
		if hasCapability(e.Capabilities, capabilityTeamCoordination) {
			capable = append(capable, e)
		}
	}
	if len(capable) > 0 {
		return capable[0], nil
	}
	if len(healthy) == 0 {
		return nil, fmt.Errorf("no healthy instance for agent %s/%s", namespace, agentName)
	}
	// During capability rollout, allow healthy instances that have not yet
	// advertised team-coordination.
	return healthy[0], nil
}

func (a *Activator) httpJoin(ctx context.Context, baseURL, sessionID string, payload []byte) error {
	return a.httpTeamCommand(ctx, baseURL, "join", CommandTeamJoin, sessionID, payload)
}

func (a *Activator) httpLeave(ctx context.Context, baseURL, sessionID string, payload []byte) error {
	return a.httpTeamCommand(ctx, baseURL, "leave", CommandTeamLeave, sessionID, payload)
}

func (a *Activator) httpTeamCommand(ctx context.Context, baseURL, action, command, sessionID string, payload []byte) error {
	url := strings.TrimRight(baseURL, "/") + "/agentscope/teams/" + action
	body := map[string]any{
		"sessionId": sessionID,
		"command":   command,
		"params":    json.RawMessage(payload),
	}
	b, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := a.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("HTTP %s %s: %s", command, resp.Status, string(msg))
	}
	return nil
}

func buildManagedWakeText(team *store.Team, member *store.TeamMember, teamCtx *TeamContext) string {
	role := member.MemberName
	if teamCtx != nil && teamCtx.MyRole != "" {
		role = teamCtx.MyRole
	}
	obj := team.Objective
	if obj == "" {
		obj = "(none)"
	}
	prompt := strings.TrimSpace(member.Prompt)
	var b strings.Builder
	fmt.Fprintf(&b, "Team %q started. Your role: %s.\nObjective: %s", team.Name, role, obj)
	if prompt != "" {
		fmt.Fprintf(&b, "\nRole prompt: %s", prompt)
	}
	if teamCtx != nil && teamCtx.IsLead {
		b.WriteString("\nYou are the lead: create/assign tasks and coordinate teammates.")
	} else {
		b.WriteString("\nClaim unassigned tasks or wait for assignment; complete your work and report results.")
	}
	return b.String()
}

func hasCapability(caps []string, want string) bool {
	for _, c := range caps {
		if c == want {
			return true
		}
	}
	return false
}
