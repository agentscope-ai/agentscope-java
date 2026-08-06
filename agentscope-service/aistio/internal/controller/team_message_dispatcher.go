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

package controller

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/go-logr/logr"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/team"
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

	// Everything queued for one managed member is delivered by a single wake: a
	// wake starts a turn, and a second wake against a running turn is rejected, so
	// waking per message would spend one turn on the first report and leave the
	// rest waiting behind it.
	managed := newManagedBatches()

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
			managed.add(member, msg)
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

	d.deliverManagedBatches(ctx, logger, managed)
}

// managedBatch is everything pending for one managed member this tick.
type managedBatch struct {
	member *store.TeamMember
	msgs   []*store.TeamMessage
}

type managedBatches struct {
	order []string
	byKey map[string]*managedBatch
}

func newManagedBatches() *managedBatches {
	return &managedBatches{byKey: map[string]*managedBatch{}}
}

func (b *managedBatches) add(member *store.TeamMember, msg *store.TeamMessage) {
	key := msg.Namespace + "/" + msg.TeamName + "/" + msg.ToMember
	batch, ok := b.byKey[key]
	if !ok {
		batch = &managedBatch{member: member}
		b.byKey[key] = batch
		b.order = append(b.order, key)
	}
	batch.msgs = append(batch.msgs, msg)
}

func (d *TeamMessageDispatcher) deliverManagedBatches(ctx context.Context, logger logr.Logger, batches *managedBatches) {
	for _, key := range batches.order {
		batch := batches.byKey[key]
		first := batch.msgs[0]
		if err := d.deliverManaged(ctx, batch.member, batch.msgs); err != nil {
			// Busy is backpressure, not a failed attempt: the member is mid-turn, and
			// these notices must still be waiting when it goes idle.
			if errors.Is(err, team.ErrMemberBusy) {
				logger.V(1).Info("member busy, keeping messages queued",
					"member", first.ToMember, "count", len(batch.msgs))
				continue
			}
			logger.Error(err, "managed delivery failed", "member", first.ToMember)
			for _, msg := range batch.msgs {
				metrics.RecordTeamMessage(msg.Namespace, msg.TeamName, "failed")
				_ = d.Store.TeamMessages().IncrementAttempts(ctx, msg.ID)
			}
			continue
		}
		for _, msg := range batch.msgs {
			if err := d.Store.TeamMessages().MarkDelivered(ctx, msg.ID); err != nil {
				logger.Error(err, "failed to mark delivered", "id", msg.ID)
				continue
			}
			metrics.RecordTeamMessage(msg.Namespace, msg.TeamName, "delivered")
		}
	}
}

func (d *TeamMessageDispatcher) deliverManaged(ctx context.Context, member *store.TeamMember, msgs []*store.TeamMessage) error {
	if d.ManagedWake == nil {
		return fmt.Errorf("managed wake not configured")
	}
	if member.ManagedSessionID == "" || member.OwnerID == "" {
		return fmt.Errorf("member %s missing managedSessionId/ownerId", member.MemberName)
	}
	return d.ManagedWake.PostSessionWakeEvent(ctx, member.ManagedSessionID, member.OwnerID,
		renderWakeText(msgs))
}

// renderWakeText turns a member's pending mailbox into the text of one wake.
func renderWakeText(msgs []*store.TeamMessage) string {
	if len(msgs) == 1 && msgs[0].Content == "" {
		return fmt.Sprintf("[team:%s] you have a new team message as %s",
			msgs[0].TeamName, msgs[0].ToMember)
	}
	var b strings.Builder
	if len(msgs) > 1 {
		fmt.Fprintf(&b, "[team:%s] %d team notifications arrived while you were busy."+
			" Handle all of them in this turn.\n\n", msgs[0].TeamName, len(msgs))
	}
	for i, msg := range msgs {
		if i > 0 {
			b.WriteString("\n\n")
		}
		fmt.Fprintf(&b, "[team:%s from %s] %s", msg.TeamName, msg.FromMember, msg.Content)
	}
	return b.String()
}
