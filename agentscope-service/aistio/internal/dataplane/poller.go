package dataplane

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// Poller walks the self-registration registry and pulls Level-1 session
// snapshots into the runtime store. It does not depend on controller-runtime,
// so it runs in standalone (no-Kubernetes) mode.
type Poller struct {
	Registry *Registry
	Store    store.Store
	Prober   prober.DataPlaneProber
	Interval time.Duration
}

// Run blocks until ctx is cancelled.
func (p *Poller) Run(ctx context.Context) {
	if p.Interval <= 0 {
		p.Interval = 15 * time.Second
	}
	ticker := time.NewTicker(p.Interval)
	defer ticker.Stop()
	p.tick(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.tick(ctx)
		}
	}
}

func (p *Poller) tick(ctx context.Context) {
	now := time.Now().UTC()
	for _, id := range p.Registry.MarkStale(now) {
		log.Printf("dataplane poller: instance %s marked unhealthy (heartbeat timeout)", id)
	}
	for _, e := range p.Registry.List() {
		if !e.Healthy || e.ContractLevel < 2 || e.BaseURL == "" {
			continue
		}
		p.pollOne(ctx, e)
	}
}

func (p *Poller) pollOne(ctx context.Context, e *Entry) {
	snaps, err := p.Prober.ProbeSessions(ctx, e.BaseURL)
	if err != nil {
		log.Printf("dataplane poller: probe sessions %s (%s): %v", e.InstanceID, e.BaseURL, err)
		return
	}
	keep := make([]string, 0, len(snaps))
	for _, snap := range snaps {
		keep = append(keep, snap.ID)
		sess := &store.Session{
			SessionID:        snap.ID,
			AgentName:        e.AgentName,
			Namespace:        e.Namespace,
			Framework:        firstNonEmpty(snap.Framework, e.Framework),
			FrameworkVersion: snap.FrameworkVersion,
			Phase:            firstNonEmpty(snap.Phase, store.SessionPhaseActive),
			InstanceRef:      e.InstanceID,
		}
		if snap.StartedAt != "" {
			if t, err := time.Parse(time.RFC3339, snap.StartedAt); err == nil {
				sess.StartedAt = &t
			}
		}
		if snap.LastActiveAt != "" {
			if t, err := time.Parse(time.RFC3339, snap.LastActiveAt); err == nil {
				sess.LastActiveAt = &t
			}
		}
		stored, err := p.Store.Sessions().Upsert(ctx, sess)
		if err != nil {
			log.Printf("dataplane poller: upsert session %s: %v", snap.ID, err)
			continue
		}
		var prompt, completion int64
		if snap.TokenUsage != nil {
			prompt = snap.TokenUsage.PromptTokens
			completion = snap.TokenUsage.CompletionTokens
		}
		var taskSummary json.RawMessage
		if snap.TaskSummary != nil {
			taskSummary, _ = json.Marshal(snap.TaskSummary)
		}
		_ = p.Store.Metrics().RecordSnapshot(ctx, &store.SessionSnapshot{
			SessionFK:             stored.ID,
			CapturedAt:            time.Now().UTC(),
			MessageCount:          snap.MessageCount,
			PromptTokens:          prompt,
			CompletionTokens:      completion,
			TotalTokens:           prompt + completion,
			ContextPressure:       snap.ContextPressure,
			IsCompacted:           snap.IsCompacted,
			EffectiveMessageCount: snap.EffectiveMessageCount,
			ContextHash:           snap.ContextHash,
			TaskSummary:           taskSummary,
		})
		_ = p.Store.Metrics().RecordTokenUsage(ctx, &store.TokenUsageMetric{
			SessionFK:        &stored.ID,
			AgentName:        e.AgentName,
			Namespace:        e.Namespace,
			PromptTokens:     prompt,
			CompletionTokens: completion,
			TotalTokens:      prompt + completion,
			RecordedAt:       time.Now().UTC(),
		})

		if snap.ContextHash != "" && hasCap(e.Capabilities, "context-query") {
			prev, err := p.Store.ContextSnapshots().Latest(ctx, stored.ID)
			if err == store.ErrNotFound || (err == nil && prev.ContextHash != snap.ContextHash) {
				if live, err := p.Prober.FetchContext(ctx, e.BaseURL, snap.ID); err == nil {
					if row, err := live.ToStoreContext(stored.ID, stored.Framework); err == nil {
						_, _ = p.Store.ContextSnapshots().PutIfChanged(ctx, row)
					}
				}
			}
		}
	}
	_, _ = p.Store.Sessions().TerminateMissing(ctx, e.AgentName, e.Namespace, keep, 60*time.Second)
}

func hasCap(caps []string, want string) bool {
	for _, c := range caps {
		if c == want {
			return true
		}
	}
	return false
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
