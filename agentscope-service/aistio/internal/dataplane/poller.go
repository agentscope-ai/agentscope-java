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

package dataplane

import (
	"context"
	"encoding/json"
	"log"
	"strings"
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
	snaps, truncated, err := probeSessions(ctx, p.Prober, e.BaseURL)
	if err != nil {
		log.Printf("dataplane poller: probe sessions %s (%s): %v", e.InstanceID, e.BaseURL, err)
		return
	}
	keep := make([]string, 0, len(snaps))
	var activeCount int32
	var intervalTokens int64
	var pressureSum float64
	var pressureN int
	for _, snap := range snaps {
		keep = append(keep, snap.ID)
		phase := strings.ToLower(firstNonEmpty(snap.Phase, store.SessionPhaseActive))
		busy := deriveBusy(snap.Busy, phase)
		sess := &store.Session{
			SessionID:        snap.ID,
			AgentName:        e.AgentName,
			Namespace:        e.Namespace,
			Framework:        firstNonEmpty(snap.Framework, e.Framework),
			FrameworkVersion: snap.FrameworkVersion,
			Phase:            phase,
			Busy:             busy,
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
		if err := p.Store.Turns().SyncOnPhase(ctx, stored.ID, phase); err != nil {
			log.Printf("dataplane poller: sync turn %s: %v", snap.ID, err)
		}
		if phase == store.SessionPhaseActive {
			activeCount++
		}
		var prompt, completion int64
		if snap.TokenUsage != nil {
			prompt = snap.TokenUsage.PromptTokens
			completion = snap.TokenUsage.CompletionTokens
		}
		prevSnap, _ := p.Store.Metrics().LatestSnapshot(ctx, stored.ID)
		dPrompt, dCompletion := store.TokenUsageDelta(prevSnap, prompt, completion)
		intervalTokens += dPrompt + dCompletion
		if snap.ContextPressure > 0 {
			pressureSum += snap.ContextPressure
			pressureN++
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
		// Narrow transcript index from Level-1 snapshot aggregates (not events).
		_ = store.UpsertTranscriptIndexFromSnapshot(ctx, p.Store, stored.ID, snap.MessageCount, prompt, completion)
		if dPrompt > 0 || dCompletion > 0 {
			tok := &store.TokenUsageMetric{
				SessionFK:        &stored.ID,
				AgentName:        e.AgentName,
				Namespace:        e.Namespace,
				PromptTokens:     dPrompt,
				CompletionTokens: dCompletion,
				TotalTokens:      dPrompt + dCompletion,
				RecordedAt:       time.Now().UTC(),
			}
			if snap.Model != "" {
				tok.Model = snap.Model
			}
			_ = p.Store.Metrics().RecordTokenUsage(ctx, tok)
		}

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
	avgPressure := 0.0
	if pressureN > 0 {
		avgPressure = pressureSum / float64(pressureN)
	}
	_ = p.Store.Metrics().RecordAgentMetric(ctx, &store.AgentMetric{
		AgentName:          e.AgentName,
		Namespace:          e.Namespace,
		RecordedAt:         time.Now().UTC(),
		ActiveSessions:     activeCount,
		TotalTokens:        intervalTokens,
		AvgContextPressure: avgPressure,
	})
	// Skip ArchiveMissing when the probe page looks truncated — otherwise
	// sessions omitted by a silent max page size would be mis-archived.
	if truncated {
		log.Printf("dataplane poller: skipping ArchiveMissing for %s/%s: probe returned %d sessions (truncated)",
			e.Namespace, e.AgentName, len(snaps))
	} else {
		_, _ = p.Store.Sessions().ArchiveMissing(ctx, e.AgentName, e.Namespace, keep, 60*time.Second)
	}
	// TTL archive: idle sessions with no activity for 7d move to History (archived).
	_, _ = p.Store.Sessions().ArchiveIdleOlderThan(ctx, 7*24*time.Hour)
}

// detailedSessionsProber is implemented by HTTPProber.
type detailedSessionsProber interface {
	ProbeSessionsDetailed(ctx context.Context, endpoint string) (prober.SessionsProbeResult, error)
}

func probeSessions(ctx context.Context, p prober.DataPlaneProber, endpoint string) ([]prober.SessionSnapshot, bool, error) {
	if dp, ok := p.(detailedSessionsProber); ok {
		res, err := dp.ProbeSessionsDetailed(ctx, endpoint)
		if err != nil {
			return nil, false, err
		}
		return res.Sessions, res.LikelyTruncated(), nil
	}
	snaps, err := p.ProbeSessions(ctx, endpoint)
	if err != nil {
		return nil, false, err
	}
	return snaps, prober.SessionsProbeLikelyTruncated(len(snaps)), nil
}

// deriveBusy implements the busy/phase resolution chain:
// 1. DP reported busy → use it
// 2. else derive from phase == active
// 3. else nil (unknown)
func deriveBusy(reported *bool, phase string) *bool {
	if reported != nil {
		return reported
	}
	lower := strings.ToLower(phase)
	if lower == "" {
		return nil
	}
	b := lower == store.SessionPhaseActive || lower == "active"
	return &b
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
