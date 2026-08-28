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

package runtimehost

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

type Config struct {
	Registration      Registration
	PollInterval      time.Duration
	HeartbeatInterval time.Duration
	LeaseTTL          time.Duration
	WorkspaceRoot     string
	StateRoot         string
}

type Engine struct {
	Config    Config
	Client    ControlPlaneClient
	Providers map[string]provider.Adapter

	mu           sync.RWMutex
	host         *controlmodel.RuntimeHost
	capabilities json.RawMessage
	active       atomic.Int32
	workers      sync.WaitGroup
}

const journalEventLimit = 500

func (e *Engine) Run(ctx context.Context) error {
	if e.Client == nil || len(e.Providers) == 0 {
		return fmt.Errorf("runtime host client and providers are required")
	}
	if e.Config.Registration.Capacity <= 0 {
		e.Config.Registration.Capacity = 1
	}
	if e.Config.PollInterval <= 0 {
		e.Config.PollInterval = 2 * time.Second
	}
	if e.Config.HeartbeatInterval <= 0 {
		e.Config.HeartbeatInterval = 15 * time.Second
	}
	if e.Config.LeaseTTL <= 0 {
		e.Config.LeaseTTL = 30 * time.Second
	}
	if e.Config.Registration.OS == "" {
		e.Config.Registration.OS = runtime.GOOS
	}
	if e.Config.Registration.Arch == "" {
		e.Config.Registration.Arch = runtime.GOARCH
	}
	if err := e.detectProviders(ctx); err != nil {
		return err
	}
	if err := e.register(ctx); err != nil {
		return err
	}

	heartbeatCtx, heartbeatCancel := context.WithCancel(ctx)
	defer heartbeatCancel()
	go e.heartbeatLoop(heartbeatCtx)

	for {
		if err := ctx.Err(); err != nil {
			e.workers.Wait()
			return nil
		}
		if e.active.Load() >= e.Config.Registration.Capacity {
			if !waitContext(ctx, e.Config.PollInterval) {
				continue
			}
			continue
		}
		host := e.currentHost()
		if host == nil {
			if err := e.register(ctx); err != nil {
				if !waitContext(ctx, e.Config.PollInterval) {
					continue
				}
				continue
			}
			host = e.currentHost()
		}
		leaseToken := uuid.NewString()
		work, err := e.Client.Claim(ctx, host, e.Config.Registration.HostKey+"/"+leaseToken, leaseToken, e.Config.LeaseTTL)
		switch {
		case err == nil:
			e.active.Add(1)
			e.workers.Add(1)
			go func() {
				defer e.workers.Done()
				defer e.active.Add(-1)
				e.execute(ctx, host.ID, work)
			}()
		case errors.Is(err, ErrNoWork):
			waitContext(ctx, e.Config.PollInterval)
		default:
			e.clearHost()
			waitContext(ctx, e.Config.PollInterval)
		}
	}
}

func (e *Engine) detectProviders(ctx context.Context) error {
	versions := make(map[string]string)
	for name, adapter := range e.Providers {
		version, err := adapter.Detect(ctx)
		if err != nil {
			return fmt.Errorf("detect provider %s: %w", name, err)
		}
		versions[name] = version
	}
	capabilities, err := json.Marshal(map[string]any{"providers": versions})
	if err != nil {
		return err
	}
	e.capabilities = capabilities
	e.Config.Registration.Capabilities = capabilities
	return nil
}

func (e *Engine) register(ctx context.Context) error {
	host, err := e.Client.Register(ctx, e.Config.Registration)
	if err != nil {
		return err
	}
	e.mu.Lock()
	e.host = host
	e.mu.Unlock()
	return nil
}

func (e *Engine) currentHost() *controlmodel.RuntimeHost {
	e.mu.RLock()
	defer e.mu.RUnlock()
	if e.host == nil {
		return nil
	}
	cp := *e.host
	return &cp
}

func (e *Engine) clearHost() {
	e.mu.Lock()
	e.host = nil
	e.mu.Unlock()
}

func (e *Engine) heartbeatLoop(ctx context.Context) {
	ticker := time.NewTicker(e.Config.HeartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			host := e.currentHost()
			if host == nil {
				continue
			}
			updated, err := e.Client.Heartbeat(ctx, host, e.active.Load(), e.capabilities)
			if err != nil {
				e.clearHost()
				continue
			}
			e.mu.Lock()
			e.host = updated
			e.mu.Unlock()
		}
	}
}

func (e *Engine) execute(parent context.Context, hostID uuid.UUID, work *ClaimedWork) {
	execution := work.Attempt
	record := &JournalRecord{Attempt: execution, Task: work.Task, Context: work.Context}
	journal := &Journal{Root: e.Config.StateRoot}
	_ = journal.Save(record)
	if err := e.Client.Prepare(parent, hostID, execution); err != nil {
		e.failAndFinalize(parent, journal, hostID, execution, "prepare_failed", err.Error(), nil)
		return
	}
	workspace := &WorkspaceManager{Root: e.Config.WorkspaceRoot}
	path, key, prompt, err := workspace.Prepare(parent, work.Context)
	if err != nil {
		_ = e.Client.Fail(parent, hostID, execution, "workspace_prepare_failed", err.Error(), nil)
		_ = journal.Remove(execution.ID)
		return
	}
	record.Workspace = path
	_ = journal.Save(record)
	if work.Profile == nil {
		_ = e.Client.Fail(parent, hostID, execution, "runtime_profile_missing", "claim did not include a runtime profile", nil)
		_ = journal.Remove(execution.ID)
		return
	}
	adapter, ok := e.Providers[work.Profile.Provider]
	if !ok {
		_ = e.Client.Fail(parent, hostID, execution, "provider_unavailable", "provider "+work.Profile.Provider+" is not installed", nil)
		_ = journal.Remove(execution.ID)
		return
	}
	if err := e.Client.Start(parent, hostID, execution, execution.ProviderSessionID, key); err != nil {
		e.failAndFinalize(parent, journal, hostID, execution, "start_failed", err.Error(), nil)
		return
	}
	runCtx, cancel := context.WithCancel(parent)
	defer cancel()
	go e.renewLoop(runCtx, cancel, hostID, execution)
	result, runErr := adapter.Run(runCtx, provider.Request{
		Prompt: prompt, Workspace: path, ProviderSessionID: execution.ProviderSessionID,
		Configuration: work.Profile.Configuration,
	}, func(event provider.Event) error {
		record.Events = append(record.Events, event)
		if overflow := len(record.Events) - journalEventLimit; overflow > 0 {
			record.Events = append([]provider.Event(nil), record.Events[overflow:]...)
		}
		if err := journal.Save(record); err != nil {
			return err
		}
		if event.ProviderSessionID != "" && event.ProviderSessionID != execution.ProviderSessionID {
			checkpoint, _ := json.Marshal(map[string]string{"providerSessionId": event.ProviderSessionID})
			if err := e.Client.Checkpoint(runCtx, hostID, execution, event.ProviderSessionID, checkpoint); err != nil {
				return err
			}
		}
		return nil
	})
	if execution.State == controlmodel.ExecutionCancelRequested {
		if err := e.Client.Cancelled(context.WithoutCancel(parent), hostID, execution); err == nil {
			_ = journal.Remove(execution.ID)
		}
		return
	}
	if runErr != nil {
		e.failAndFinalize(parent, journal, hostID, execution, "provider_failed", runErr.Error(), nil)
		return
	}
	if result.ProviderSessionID != "" && result.ProviderSessionID != execution.ProviderSessionID {
		if err := e.Client.Checkpoint(context.WithoutCancel(parent), hostID, execution,
			result.ProviderSessionID, result.Checkpoint); err != nil {
			e.failAndFinalize(parent, journal, hostID, execution, "checkpoint_failed", err.Error(), result.Checkpoint)
			return
		}
	}
	resultJSON, _ := json.Marshal(map[string]any{"output": result.Output})
	if err := e.Client.Complete(context.WithoutCancel(parent), hostID, execution, resultJSON, result.Checkpoint); err == nil {
		_ = journal.Remove(execution.ID)
	}
}

func (e *Engine) failAndFinalize(parent context.Context, journal *Journal, hostID uuid.UUID,
	execution *controlmodel.ExecutionAttempt, code, message string, checkpoint json.RawMessage) {
	if err := e.Client.Fail(context.WithoutCancel(parent), hostID, execution, code, message, checkpoint); err == nil {
		_ = journal.Remove(execution.ID)
	}
}

func (e *Engine) renewLoop(ctx context.Context, cancel context.CancelFunc, hostID uuid.UUID, execution *controlmodel.ExecutionAttempt) {
	interval := e.Config.LeaseTTL / 3
	if interval < time.Second {
		interval = time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := e.Client.Renew(ctx, hostID, execution, e.Config.LeaseTTL); err != nil {
				cancel()
				return
			}
			if execution.State == controlmodel.ExecutionCancelRequested {
				cancel()
				return
			}
		}
	}
}

func waitContext(ctx context.Context, duration time.Duration) bool {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
