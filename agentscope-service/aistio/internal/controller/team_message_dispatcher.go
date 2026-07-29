package controller

import (
	"context"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// TeamEventDeliverer sends team events to connected members.
// Implemented by asdp.Distributor wrapper.
type TeamEventDeliverer interface {
	DeliverTeamEvent(namespace, instanceID, teamID, eventType, memberName, content string) error
	GetConnectedInstance(namespace, agentName string) (instanceID string, ok bool)
}

// TeamMessageDispatcher polls the store's TeamMessage outbox and delivers
// pending messages to connected data plane instances. It runs on ALL
// replicas (NeedLeaderElection = false) so it can reach connections held by
// whichever replica currently owns them.
type TeamMessageDispatcher struct {
	Store       store.Store
	Deliverer   TeamEventDeliverer
	Interval    time.Duration // default 2s
	MaxAttempts int32         // default 5
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
	if d.Store == nil || d.Deliverer == nil {
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
			// No specific recipient resolved; nothing further to deliver.
			_ = d.Store.TeamMessages().MarkDelivered(ctx, msg.ID)
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
