package team

import (
	"context"
	"fmt"
	"sync"

	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

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
