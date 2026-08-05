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
	"sync"

	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// LeadMemberName is the reserved member name of a team's lead.
const LeadMemberName = "lead"

// MemberLocation holds the routing information for a team member.
type MemberLocation struct {
	MemberName  string
	AgentName   string
	InstanceRef string
	InstanceIP  string
	SessionID   string
	Connected   bool
}

// MessageRouter routes messages between team members through the store's
// TeamMessage outbox, which the TeamMessageDispatcher delivers to whichever
// replica holds the recipient's live gRPC connection. It also keeps an
// in-memory registry purely for informational REST listing — routing never
// depends on it, so it is safe across replicas and restarts.
type MessageRouter struct {
	mu        sync.RWMutex
	locations map[string]map[string]*MemberLocation // teamName -> memberName -> location

	messages store.TeamMessageRepository
	sessions store.SessionRepository
}

// NewMessageRouter creates a new store-backed MessageRouter.
func NewMessageRouter(messages store.TeamMessageRepository, sessions store.SessionRepository) *MessageRouter {
	return &MessageRouter{
		locations: make(map[string]map[string]*MemberLocation),
		messages:  messages,
		sessions:  sessions,
	}
}

// RegisterMember records a member's location for informational listing.
func (r *MessageRouter) RegisterMember(teamName string, loc *MemberLocation) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.locations[teamName] == nil {
		r.locations[teamName] = make(map[string]*MemberLocation)
	}
	r.locations[teamName][loc.MemberName] = loc
}

// UnregisterMember removes a member from the informational registry.
func (r *MessageRouter) UnregisterMember(teamName, memberName string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if locs := r.locations[teamName]; locs != nil {
		delete(locs, memberName)
	}
}

// GetMemberLocation returns the last known location of a team member.
func (r *MessageRouter) GetMemberLocation(teamName, memberName string) (*MemberLocation, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	locs := r.locations[teamName]
	if locs == nil {
		return nil, fmt.Errorf("team %s not found in router", teamName)
	}
	loc, ok := locs[memberName]
	if !ok {
		return nil, fmt.Errorf("member %s not found in team %s", memberName, teamName)
	}
	return loc, nil
}

// ListMembers returns all registered members for a team.
func (r *MessageRouter) ListMembers(teamName string) []*MemberLocation {
	r.mu.RLock()
	defer r.mu.RUnlock()

	locs := r.locations[teamName]
	result := make([]*MemberLocation, 0, len(locs))
	for _, loc := range locs {
		result = append(result, loc)
	}
	return result
}

// RouteMessage routes a message from one member to another by writing an
// undelivered TeamMessage to the store outbox. Delivery and connectivity are
// handled asynchronously by the TeamMessageDispatcher, so this does not
// require the recipient to be connected to this replica.
func (r *MessageRouter) RouteMessage(namespace, teamName, from, to, content string) (*store.TeamMessage, error) {
	msg := &store.TeamMessage{
		TeamName:   teamName,
		Namespace:  namespace,
		FromMember: from,
		ToMember:   to,
		Content:    content,
		Kind:       "message",
	}
	if err := r.messages.Send(context.Background(), msg); err != nil {
		return nil, fmt.Errorf("sending team message: %w", err)
	}
	metrics.RecordTeamMessage(namespace, teamName, "enqueued")
	return msg, nil
}

// NotifyLead enqueues a notice toward the team lead so lifecycle transitions
// (task completed/failed, member lost) reach the lead instead of only landing in
// the store. Best-effort: never fails the caller's primary operation, and skips
// self-notification when the lead itself is the source.
func (r *MessageRouter) NotifyLead(namespace, teamName, from, content string) {
	if r == nil || from == LeadMemberName || content == "" {
		return
	}
	if _, err := r.RouteMessage(namespace, teamName, from, LeadMemberName, content); err != nil {
		log.Log.WithName("team-router").V(1).Info("lead notify failed",
			"team", teamName, "from", from, "error", err.Error())
	}
}

// NotifyMember enqueues a notice toward a single member so board transitions
// that create work for it (assignment, board-driven start) wake it instead of
// waiting for it to poll again. Best-effort, and skips self-notification.
func (r *MessageRouter) NotifyMember(namespace, teamName, from, to, content string) {
	if r == nil || to == "" || to == from || content == "" {
		return
	}
	if _, err := r.RouteMessage(namespace, teamName, from, to, content); err != nil {
		log.Log.WithName("team-router").V(1).Info("member notify failed",
			"team", teamName, "to", to, "error", err.Error())
	}
}

// BroadcastMessage sends a message to every team member (except the sender)
// by writing one point-to-point TeamMessage per recipient. Recipients are
// derived from the team's sessions in the store (the persistent source of
// truth), not the in-memory registry.
func (r *MessageRouter) BroadcastMessage(namespace, teamName, from, content string) ([]*store.TeamMessage, error) {
	recipients, err := r.teamMemberRoles(namespace, teamName)
	if err != nil {
		return nil, err
	}

	var msgs []*store.TeamMessage
	for _, to := range recipients {
		if to == from {
			continue
		}
		msg := &store.TeamMessage{
			TeamName:   teamName,
			Namespace:  namespace,
			FromMember: from,
			ToMember:   to,
			Content:    content,
			Kind:       "message",
		}
		if err := r.messages.Send(context.Background(), msg); err != nil {
			return msgs, fmt.Errorf("creating broadcast message for %s: %w", to, err)
		}
		metrics.RecordTeamMessage(namespace, teamName, "enqueued")
		msgs = append(msgs, msg)
	}
	return msgs, nil
}

// teamMemberRoles returns the distinct team-role values for the team's
// sessions in the store (e.g. "lead" and each member name).
func (r *MessageRouter) teamMemberRoles(namespace, teamName string) ([]string, error) {
	sessions, err := r.sessions.List(context.Background(), store.SessionFilter{
		Namespace: namespace,
		TeamID:    teamName,
	})
	if err != nil {
		return nil, fmt.Errorf("listing team sessions: %w", err)
	}
	seen := make(map[string]struct{})
	var roles []string
	for _, s := range sessions {
		if s.TeamRole == "" {
			continue
		}
		if _, ok := seen[s.TeamRole]; ok {
			continue
		}
		seen[s.TeamRole] = struct{}{}
		roles = append(roles, s.TeamRole)
	}
	return roles, nil
}

// GetMessageHistory returns recent messages for a team from the store.
func (r *MessageRouter) GetMessageHistory(namespace, teamName string, limit int) []*store.TeamMessage {
	msgs, err := r.messages.History(context.Background(), teamName, namespace, limit)
	if err != nil {
		return nil
	}
	return msgs
}

// DeleteTeam clears in-memory routing state and store-backed messages for a team.
func (r *MessageRouter) DeleteTeam(teamName, namespace string) {
	r.mu.Lock()
	delete(r.locations, teamName)
	r.mu.Unlock()
	_ = r.messages.DeleteByTeam(context.Background(), teamName, namespace)
}
