package controller

import (
	"context"
	"fmt"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// TeamEventDeliverer sends team events to connected BYO members.
// Implemented by asdp.Distributor.
type TeamEventDeliverer interface {
	DeliverTeamEvent(namespace, instanceID, teamID, eventType, memberName, content string) error
	GetConnectedInstance(namespace, agentName string) (instanceID string, ok bool)
}

// ManagedWakeAPI posts a user.message wake into a Managed product session.
// Implemented by product.Server.
type ManagedWakeAPI interface {
	PostSessionWakeEvent(ctx context.Context, sessionID, ownerID, text string) error
}

// TeamMessageDispatcher polls the store's TeamMessage outbox and delivers
// pending messages. Managed members are woken via ManagedWake; BYO members
// use ASDP when Deliverer is set. Runs on ALL replicas (NeedLeaderElection =
// false) so it can reach ASDP connections held by any replica.
type TeamMessageDispatcher struct {
	Store       store.Store
	Deliverer   TeamEventDeliverer // optional ASDP backend
	ManagedWake ManagedWakeAPI     // optional Managed product wake
	Interval    time.Duration      // default 2s
	MaxAttempts int32              // default 5
}

// Start implements manager.Runnable. It ticks at Interval, dispatching
// pending team messages until ctx is cancelled.
func (d *TeamMessageDispatcher) Start(ctx context.Context) error {
	interval := d.Interval
	if interval <= 0 {
		interval = 2 * time.Second
	}
	maxAttempts := d.MaxAttempts
	if maxAttempts <= 0 {
		maxAttempts = 5
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			d.dispatchOnce(ctx, maxAttempts)
		}
	}
}

// NeedLeaderElection opts this runnable out of leader gating so it runs on
// every replica.
func (d *TeamMessageDispatcher) NeedLeaderElection() bool { return false }

func (d *TeamMessageDispatcher) dispatchOnce(ctx context.Context, maxAttempts int32) {
	if d.Store == nil {
		return
	}
	if d.Deliverer == nil && d.ManagedWake == nil {
		return
	}
	logger := log.FromContext(ctx).WithName("team-message-dispatcher")

	msgs, err := d.Store.TeamMessages().ListPendingAll(ctx, 100)
	if err != nil {
		logger.Error(err, "failed to list pending team messages")
		return
	}

	for _, msg := range msgs {
		if msg.Attempts >= maxAttempts {
			logger.Info("message exceeded max attempts, dropping", "id", msg.ID, "team", msg.TeamName)
			metrics.RecordTeamMessage(msg.Namespace, msg.TeamName, "dropped")
			if err := d.Store.TeamMessages().MarkDelivered(ctx, msg.ID); err != nil {
				logger.Error(err, "failed to mark dropped message as handled", "id", msg.ID)
			}
			continue
		}

		if msg.ToMember == "" {
			_ = d.Store.TeamMessages().MarkDelivered(ctx, msg.ID)
			continue
		}

		member, mErr := d.Store.Teams().GetMember(ctx, msg.Namespace, msg.TeamName, msg.ToMember)
		if mErr == nil && member != nil && member.DeployMode == store.MemberDeployManaged {
			if err := d.deliverManaged(ctx, member, msg); err != nil {
				logger.Error(err, "managed delivery failed", "id", msg.ID)
				metrics.RecordTeamMessage(msg.Namespace, msg.TeamName, "failed")
				_ = d.Store.TeamMessages().IncrementAttempts(ctx, msg.ID)
				continue
			}
			if err := d.Store.TeamMessages().MarkDelivered(ctx, msg.ID); err != nil {
				logger.Error(err, "failed to mark delivered", "id", msg.ID)
				continue
			}
			metrics.RecordTeamMessage(msg.Namespace, msg.TeamName, "delivered")
			continue
		}

		if d.Deliverer == nil {
			_ = d.Store.TeamMessages().IncrementAttempts(ctx, msg.ID)
			continue
		}

		sessions, err := d.Store.Sessions().List(ctx, store.SessionFilter{
			Namespace: msg.Namespace,
			TeamID:    msg.TeamName,
			TeamRole:  msg.ToMember,
		})
		if err != nil || len(sessions) == 0 {
			_ = d.Store.TeamMessages().IncrementAttempts(ctx, msg.ID)
			continue
		}

		agentName := sessions[0].AgentName
		instanceID, connected := d.Deliverer.GetConnectedInstance(msg.Namespace, agentName)
		if !connected {
			// Not on this replica -- another replica may hold the connection.
			continue
		}

		if err := d.Deliverer.DeliverTeamEvent(msg.Namespace, instanceID, msg.TeamName, msg.Kind, msg.ToMember, msg.Content); err != nil {
			logger.Error(err, "delivery failed", "id", msg.ID)
			metrics.RecordTeamMessage(msg.Namespace, msg.TeamName, "failed")
			if err := d.Store.TeamMessages().IncrementAttempts(ctx, msg.ID); err != nil {
				logger.Error(err, "failed to increment attempts", "id", msg.ID)
			}
			continue
		}

		if err := d.Store.TeamMessages().MarkDelivered(ctx, msg.ID); err != nil {
			logger.Error(err, "failed to mark delivered", "id", msg.ID)
			continue
		}
		metrics.RecordTeamMessage(msg.Namespace, msg.TeamName, "delivered")
	}
}

func (d *TeamMessageDispatcher) deliverManaged(ctx context.Context, member *store.TeamMember, msg *store.TeamMessage) error {
	if d.ManagedWake == nil {
		return fmt.Errorf("managed wake not configured")
	}
	if member.ManagedSessionID == "" || member.OwnerID == "" {
		return fmt.Errorf("member %s missing managedSessionId/ownerId", member.MemberName)
	}
	text := msg.Content
	if text == "" {
		text = fmt.Sprintf("[team:%s] you have a new team message as %s", msg.TeamName, msg.ToMember)
	} else {
		text = fmt.Sprintf("[team:%s from %s] %s", msg.TeamName, msg.FromMember, msg.Content)
	}
	return d.ManagedWake.PostSessionWakeEvent(ctx, member.ManagedSessionID, member.OwnerID, text)
}
