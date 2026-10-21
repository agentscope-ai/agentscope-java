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
	"log/slog"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimehost/provider"
)

type Config struct {
	Registration      Registration
	PollInterval      time.Duration
	HeartbeatInterval time.Duration
	LeaseTTL          time.Duration
	WorkspaceRoot     string
	StateRoot         string
	ControlPlane      string
	CollaborationMCP  string
	CollaborationCLI  string
}

type Engine struct {
	Config       Config
	Client       ControlPlaneClient
	Providers    map[string]provider.Adapter
	OnRegistered func(*controlmodel.RuntimeHost) error

	mu           sync.RWMutex
	host         *controlmodel.RuntimeHost
	capabilities json.RawMessage
	active       atomic.Int32
	workers      sync.WaitGroup
	inFlight     sync.Map
	replayCursor string
}

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
		e.Config.LeaseTTL = 60 * time.Second
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
	e.replayPendingTerminals(ctx)

	heartbeatCtx, heartbeatCancel := context.WithCancel(ctx)
	defer heartbeatCancel()
	go e.heartbeatLoop(heartbeatCtx)

	for {
		if err := ctx.Err(); err != nil {
			e.workers.Wait()
			return nil
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
		// Registration and heartbeat responses carry the effective shared capacity.
		capacity := host.Capacity
		if capacity <= 0 {
			capacity = e.Config.Registration.Capacity
		}
		e.replayPendingTerminals(ctx)
		if e.active.Load() >= capacity {
			waitContext(ctx, e.Config.PollInterval)
			continue
		}
		pending, journalErr := (&Journal{Root: e.Config.StateRoot}).IDs()
		if journalErr != nil || len(pending)+int(e.active.Load()) >= maxJournalAttempts {
			waitContext(ctx, e.Config.PollInterval)
			continue
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
	descriptors := make(map[string]provider.Descriptor)
	for name, adapter := range e.Providers {
		version, err := adapter.Detect(ctx)
		if err != nil {
			return fmt.Errorf("detect provider %s: %w", name, err)
		}
		versions[name] = version
		descriptors[name] = provider.Describe(adapter)
	}
	capabilities, err := json.Marshal(map[string]any{
		"providers":            versions,
		"providerCapabilities": descriptors,
	})
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
	if e.OnRegistered != nil {
		if err := e.OnRegistered(host); err != nil {
			return fmt.Errorf("publish runtime host readiness: %w", err)
		}
	}
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
	attemptID := execution.ID
	e.inFlight.Store(execution.ID, true)
	defer e.inFlight.Delete(execution.ID)
	logAttempt("claimed", hostID, execution, "")
	record := &JournalRecord{Attempt: execution, Task: work.Task, Context: work.Context,
		HostID: hostID, AttemptToken: work.AttemptToken}
	if work.Profile != nil {
		record.ProviderName = work.Profile.Provider
	}
	journal := &Journal{Root: e.Config.StateRoot}
	if !saveHostJournal(journal, record) {
		return
	}
	logAttempt("preparing", hostID, execution, "")
	if err := e.Client.Prepare(parent, hostID, execution); err != nil {
		e.failAndFinalize(parent, journal, record, hostID, execution, "prepare_failed", err.Error(), nil)
		return
	}
	sessionID, workspaceKey := hostedWorkspaceContext(execution, work.Task)
	logAttempt("workspace_context", hostID, execution, "",
		slog.String("session_id", sessionID), slog.String("workspace_key", workspaceKey))
	workspace := &WorkspaceManager{Root: e.Config.WorkspaceRoot}
	path, key, prompt, err := workspace.PrepareForExecution(parent, work.Context,
		sessionID, workspaceKey)
	if err != nil {
		e.failAndFinalize(parent, journal, record, hostID, execution, "workspace_prepare_failed", err.Error(), nil)
		return
	}
	definitionRoot, err := MaterializeDefinition(path, work.Definition)
	if err != nil {
		e.failAndFinalize(parent, journal, record, hostID, execution, "definition_materialize_failed", err.Error(), nil)
		return
	}
	if definitionRoot != "" {
		prompt += "\n\nAgent capability context:\n" +
			"Portable Workspace files and skills are available under " + definitionRoot +
			". Read the relevant SKILL.md before using a skill."
	}
	record.Workspace = path
	if !saveHostJournal(journal, record) {
		return
	}
	if work.Profile == nil {
		e.failAndFinalize(parent, journal, record, hostID, execution, "runtime_profile_missing", "claim did not include a runtime profile", nil)
		return
	}
	adapter, ok := e.Providers[work.Profile.Provider]
	if !ok {
		e.failAndFinalize(parent, journal, record, hostID, execution, "provider_unavailable", "provider "+work.Profile.Provider+" is not installed", nil)
		return
	}
	if err := provider.ValidateDefinition(work.Definition, provider.Describe(adapter)); err != nil {
		e.failAndFinalize(parent, journal, record, hostID, execution, "workspace_capability_unsupported", err.Error(), nil)
		return
	}

	collaborationMCP, controlPlane, taskToken := e.Config.CollaborationMCP, e.Config.ControlPlane, work.TaskToken
	if credentials, ok := e.Client.(interface{ CurrentTaskToken(uuid.UUID) string }); ok {
		relay, relayErr := startTaskCredentialRelay(controlPlane, collaborationMCP, func() string {
			return credentials.CurrentTaskToken(attemptID)
		})
		if relayErr != nil {
			e.failAndFinalize(parent, journal, record, hostID, execution, "credential_relay_failed", relayErr.Error(), nil)
			return
		}
		defer relay.Close()
		collaborationMCP, controlPlane, taskToken = relay.MCP, relay.URL, relay.Token
	}
	prompt, err = appendRuntimeContext(prompt, work.Context.Task, provider.Describe(adapter),
		collaborationMCP, e.Config.CollaborationCLI, taskToken)
	if err != nil {
		e.failAndFinalize(parent, journal, record, hostID, execution, "provider_capability_missing", err.Error(), nil)
		return
	}
	if err := e.Client.Start(parent, hostID, execution, execution.ProviderSessionID, key); err != nil {
		e.failAndFinalize(parent, journal, record, hostID, execution, "start_failed", err.Error(), nil)
		return
	}
	logAttempt("provider_started", hostID, execution, work.Profile.Provider,
		slog.String("workspace_key", key))
	runCtx, cancel := context.WithCancel(parent)
	defer cancel()
	leaseFailures := make(chan error, 1)
	var attemptMutex sync.Mutex
	renewed := make(chan struct{})
	providerRequest := provider.Request{
		Prompt: prompt, Workspace: path, RuntimeStateRoot: e.Config.StateRoot,
		ProviderSessionID: execution.ProviderSessionID,
		Configuration:     work.Profile.Configuration, Definition: work.Definition,
		CustomArgs:       hostedCustomArgs(work.ExecutionOverrides),
		CollaborationMCP: collaborationMCP, CollaborationCLI: e.Config.CollaborationCLI,
		ControlPlane: controlPlane, TaskToken: taskToken,
		TaskID: work.Context.Task.ID.String(), IssueID: work.Context.Task.IssueID.String(),
		AgentID: work.Context.Task.AgentRef, RunID: work.Context.Task.OrchestrationRunID.String(),
		TeamID: taskTeamID(work.Context.Task),
	}
	if approver, ok := e.Client.(interface {
		AwaitToolApprovalRefreshing(context.Context, string, uuid.UUID, provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error)
	}); ok {
		providerRequest.ApproveTool = func(ctx context.Context, request provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
			return approver.AwaitToolApprovalRefreshing(ctx, work.Context.Task.ID.String(), attemptID, request)
		}
	} else if approver, ok := e.Client.(interface {
		AwaitToolApproval(context.Context, string, string, provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error)
	}); ok {
		providerRequest.ApproveTool = func(ctx context.Context, request provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
			return approver.AwaitToolApproval(ctx, work.Context.Task.ID.String(), work.TaskToken, request)
		}
	}
	go func() {
		defer close(renewed)
		e.renewLoop(runCtx, cancel, hostID, execution, leaseFailures, &attemptMutex)
	}()
	result, runErr := adapter.Run(runCtx, providerRequest, func(event provider.Event) error {
		attemptMutex.Lock()
		defer attemptMutex.Unlock()
		ordinal, err := journal.AppendEvent(record, event)
		if err != nil {
			return err
		}
		if publisher, ok := e.Client.(interface {
			PublishProviderEvent(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt,
				string, int64, provider.Event) error
		}); ok && shouldPublishProviderEvent(event) {
			if err := publisher.PublishProviderEvent(runCtx, hostID, execution,
				work.Profile.Provider, ordinal, event); err != nil {
				// Observability must never abort the provider process. The local
				// journal still contains the original event for daemon diagnosis.
				logAttempt("provider_event_delivery_failed", hostID, execution,
					work.Profile.Provider, slog.Any("error", err))
			} else if controlmodel.IsExecutionAttemptTerminal(execution.State) {
				// A terminal response seals the control-plane timeline. Returning an
				// error asks the adapter to stop the still-running provider promptly.
				return context.Canceled
			}
		}
		checkpointID := event.CheckpointSessionID
		if checkpointID == "" {
			checkpointID = event.ProviderSessionID
		}
		if checkpointID != "" && checkpointID != execution.ProviderSessionID {
			checkpoint, _ := json.Marshal(map[string]string{"providerSessionId": checkpointID})
			if err := e.Client.Checkpoint(runCtx, hostID, execution, checkpointID, checkpoint); err != nil {
				return err
			}
		}
		return nil
	})
	// The provider has exited. Join the renewal loop before sealing its journal
	// or submitting a terminal result; both HTTP paths update the Attempt value.
	cancel()
	<-renewed
	if controlmodel.IsExecutionAttemptTerminal(execution.State) {
		logAttempt("terminal_observed", hostID, execution, work.Profile.Provider)
		var snapshot controlmodel.RuntimeDispatchSnapshot
		_ = json.Unmarshal(execution.RuntimeBinding, &snapshot)
		if snapshot.PortalCommandID == "" {
			record.PendingTerminal = &PendingTerminal{Action: "cleanup"}
			if saveHostJournal(journal, record) {
				e.deliverPendingTerminal(context.WithoutCancel(parent), journal, record)
			}
			return
		}
		record.ProviderName = work.Profile.Provider
		record.PendingTerminal = &PendingTerminal{Action: "physical-stop"}
		if !saveHostJournal(journal, record) {
			return
		}
		e.deliverPendingTerminal(context.WithoutCancel(parent), journal, record)
		return
	}
	if execution.State == controlmodel.ExecutionCancelRequested {
		record.PendingTerminal = &PendingTerminal{Action: "cancelled"}
		if !saveHostJournal(journal, record) {
			return
		}
		e.deliverPendingTerminal(context.WithoutCancel(parent), journal, record)
		return
	}
	if runErr != nil {
		select {
		case leaseErr := <-leaseFailures:
			e.failAndFinalize(parent, journal, record, hostID, execution, "lease_lost",
				"execution lease expired while the control plane was unavailable: "+leaseErr.Error(), nil)
			return
		default:
		}
		code, message := provider.ExecutionFailure(runErr)
		e.failAndFinalize(parent, journal, record, hostID, execution, code, message, nil)
		return
	}
	if result.ProviderSessionID != "" && result.ProviderSessionID != execution.ProviderSessionID {
		if err := e.Client.Checkpoint(context.WithoutCancel(parent), hostID, execution,
			result.ProviderSessionID, result.Checkpoint); err != nil {
			e.failAndFinalize(parent, journal, record, hostID, execution, "checkpoint_failed", err.Error(), result.Checkpoint)
			return
		}
	}
	resultJSON, _ := json.Marshal(map[string]any{"output": result.Output})
	record.PendingTerminal = &PendingTerminal{Action: "complete", Result: resultJSON, Checkpoint: result.Checkpoint}
	if !saveHostJournal(journal, record) {
		return
	}
	e.deliverPendingTerminal(context.WithoutCancel(parent), journal, record)
}

func hostedWorkspaceContext(execution *controlmodel.ExecutionAttempt,
	task *controlmodel.AgentTask) (sessionID, workspaceKey string) {
	if execution == nil {
		return "", ""
	}
	sessionID, workspaceKey = execution.SessionID, execution.WorkspaceKey
	if sessionID != "" && workspaceKey != "" {
		return sessionID, workspaceKey
	}
	var snapshot controlmodel.RuntimeDispatchSnapshot
	if json.Unmarshal(execution.RuntimeBinding, &snapshot) == nil {
		if sessionID == "" {
			sessionID = snapshot.SessionID
		}
		if workspaceKey == "" && len(snapshot.Policy) > 0 {
			var policy struct {
				WorkspaceKey string `json:"workspaceKey"`
			}
			if json.Unmarshal(snapshot.Policy, &policy) == nil {
				workspaceKey = policy.WorkspaceKey
			}
		}
	}
	if sessionID == "" && task != nil {
		sessionID = task.SessionID
	}
	return sessionID, workspaceKey
}

func shouldPublishProviderEvent(event provider.Event) bool {
	var appServerEnvelope struct {
		Method string `json:"method"`
		Params struct {
			Message string `json:"message"`
		} `json:"params"`
	}
	if json.Unmarshal(event.Raw, &appServerEnvelope) == nil && appServerEnvelope.Method == "warning" &&
		strings.Contains(appServerEnvelope.Params.Message, "clamping SessionEnd hook timeout") {
		return false
	}
	var codexEnvelope struct {
		Type string `json:"type"`
		Item struct {
			Type    string `json:"type"`
			Message string `json:"message"`
		} `json:"item"`
	}
	if json.Unmarshal(event.Raw, &codexEnvelope) == nil && codexEnvelope.Type == "item.completed" &&
		codexEnvelope.Item.Type == "error" && strings.Contains(codexEnvelope.Item.Message, "clamping SessionEnd hook timeout") {
		return false
	}
	if event.Type != "system" {
		return true
	}
	var envelope struct {
		Subtype string `json:"subtype"`
	}
	if json.Unmarshal(event.Raw, &envelope) != nil {
		return true
	}
	// Qoder and Claude-compatible CLIs can emit several internal hook progress
	// records before a single model turn. They remain in the daemon journal but
	// do not add diagnostic value to the durable control-plane timeline.
	return !strings.HasPrefix(envelope.Subtype, "hook_")
}

func hostedCustomArgs(overrides *controlmodel.HostedExecutionOverrides) []string {
	if overrides == nil {
		return nil
	}
	return append([]string(nil), overrides.CustomArgs...)
}

func appendRuntimeContext(prompt string, task *controlmodel.AgentTask, descriptor provider.Descriptor,
	collaborationMCP, collaborationCLI, taskToken string) (string, error) {
	mcpAvailable := descriptor.MCP.Supported && collaborationMCP != "" && taskToken != ""
	cliAvailable := descriptor.Shell.Supported && collaborationCLI != "" && taskToken != ""
	collaborationAvailable := mcpAvailable || cliAvailable
	if task != nil && task.LeaderTask && !collaborationAvailable {
		return "", fmt.Errorf("Team leader execution requires collaboration MCP or AgentScope CLI support")
	}
	if mcpAvailable {
		prompt += "\n\nAgentScope collaboration:\n" +
			"Prefer the agentscope-collaboration MCP tools to read the current Issue, post progress, share artifacts, and coordinate durable follow-up work."
	}
	if cliAvailable {
		prompt += "\nThe task-scoped `as` CLI is also available through your shell. " +
			"Use `as task context`, `as issue current`, `as task progress --content-file <path>`, " +
			"`as task respond --content-file <path>`, and `as task run graph` as a fallback or for file-based results. " +
			"Run `as task --help` for the complete task-scoped command surface."
	}
	if task != nil && task.TeamID != nil {
		prompt += "\nYou are participating in Team " + task.TeamID.String()
		if task.TeamRole != "" {
			prompt += " as role " + task.TeamRole
		}
		prompt += "."
		if task.LeaderTask {
			prompt += " Delegate or inspect Team work through MCP or the task-scoped CLI. A blocked worker Issue is a valid outcome that requires your decision: use run.replan to retry or reassign it, issue.cancel only when a degraded/partial result is acceptable, request human action when external configuration is required, or use run.node.fail when the overall objective is unrecoverable. On a successful follow-up, call issue.accept after validating the worker result. Once all delegated Issues are accepted or explicitly skipped and work has converged, call run.node.complete and stop; that call also completes this leader Task."
		}
	}
	return prompt, nil
}

func taskTeamID(task *controlmodel.AgentTask) string {
	if task == nil || task.TeamID == nil {
		return ""
	}
	return task.TeamID.String()
}

func (e *Engine) failAndFinalize(parent context.Context, journal *Journal, record *JournalRecord,
	hostID uuid.UUID, execution *controlmodel.ExecutionAttempt, code, message string, checkpoint json.RawMessage) {
	logAttempt("failed", hostID, execution, "", slog.String("failure_code", code), slog.String("failure_message", message))
	record.PendingTerminal = &PendingTerminal{Action: "fail", FailureCode: code,
		FailureMessage: message, Checkpoint: checkpoint}
	if !saveHostJournal(journal, record) {
		return
	}
	e.deliverPendingTerminal(context.WithoutCancel(parent), journal, record)
}

func saveHostJournal(journal *Journal, record *JournalRecord) bool {
	if err := journal.Save(record); err != nil {
		logAttempt("journal_commit_failed", record.HostID, record.Attempt, record.ProviderName, slog.Any("error", err))
		return false
	}
	return true
}

func (e *Engine) replayPendingTerminals(ctx context.Context) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	j := &Journal{Root: e.Config.StateRoot}
	ids, err := j.IDs()
	if err != nil {
		return
	}
	loaded := 0
	last := e.replayCursor
	for _, id := range ids {
		if ctx.Err() != nil {
			return
		}
		if id.String() <= last {
			continue
		}
		e.replayCursor = id.String()
		if _, active := e.inFlight.Load(id); active {
			continue
		}
		record, err := j.Load(id)
		loaded++
		if err != nil || record.PendingTerminal == nil || record.HostID == uuid.Nil || record.NextDeliveryAt > time.Now().UnixMilli() {
			if loaded >= 8 {
				return
			}
			continue
		}
		if restorer, ok := e.Client.(interface {
			RestoreAttemptToken(uuid.UUID, string)
		}); ok && record.PendingTerminal.Action != "cleanup" {
			restorer.RestoreAttemptToken(record.Attempt.ID, record.AttemptToken)
		}
		e.deliverPendingTerminal(ctx, j, record)
		if loaded >= 8 {
			return
		}
	}
	e.replayCursor = ""
}

func (e *Engine) deliverPendingTerminal(ctx context.Context, journal *Journal, record *JournalRecord) {
	if record == nil || record.Attempt == nil || record.PendingTerminal == nil {
		return
	}
	terminal := record.PendingTerminal
	if terminal.Action == "cleanup" {
		_ = journal.Remove(record.Attempt.ID)
		return
	}
	var err error
	var snapshot controlmodel.RuntimeDispatchSnapshot
	_ = json.Unmarshal(record.Attempt.RuntimeBinding, &snapshot)
	if snapshot.PortalCommandID != "" {
		if publisher, ok := e.Client.(interface {
			PublishProviderEvent(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt, string, int64, provider.Event) error
		}); ok {
			end := min(journalEventCount(record), record.FinalEventsDelivered+64)
			for record.FinalEventsDelivered < end {
				event, readErr := journal.Event(record, record.FinalEventsDelivered)
				if readErr != nil {
					e.deferTerminalRetry(journal, record, readErr)
					return
				}
				if shouldPublishProviderEvent(event) {
					if err = publisher.PublishProviderEvent(ctx, record.HostID, record.Attempt, record.ProviderName, int64(record.FinalEventsDelivered+1), event); err != nil {
						e.deferTerminalRetry(journal, record, err)
						return
					}
				}
				record.FinalEventsDelivered++
			}
			record.DeliveryFailures, record.NextDeliveryAt = 0, 0
			if err = journal.Save(record); err != nil {
				return
			}
			if record.FinalEventsDelivered < journalEventCount(record) {
				return
			}
		}
	}
	switch terminal.Action {
	case "complete":
		err = e.Client.Complete(ctx, record.HostID, record.Attempt, terminal.Result, terminal.Checkpoint)
	case "fail":
		err = e.Client.Fail(ctx, record.HostID, record.Attempt, terminal.FailureCode,
			terminal.FailureMessage, terminal.Checkpoint)
	case "cancelled":
		err = e.Client.Cancelled(ctx, record.HostID, record.Attempt)
	case "physical-stop":
	default:
		return
	}
	if err == nil && snapshot.PortalCommandID != "" {
		if finalizer, ok := e.Client.(interface {
			PhysicalStopped(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt) error
		}); ok {
			err = finalizer.PhysicalStopped(ctx, record.HostID, record.Attempt)
		} else {
			err = fmt.Errorf("Portal execution requires physical-stop receipts")
		}
	}
	if err == nil {
		logAttempt("terminal_delivered", record.HostID, record.Attempt, "", slog.String("action", terminal.Action))
		record.PendingTerminal = &PendingTerminal{Action: "cleanup"}
		record.DeliveryFailures, record.NextDeliveryAt = 0, 0
		if saveHostJournal(journal, record) {
			_ = journal.Remove(record.Attempt.ID)
		}
	} else {
		if observer, ok := e.Client.(interface {
			Observe(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt) error
		}); ok && snapshot.PortalCommandID != "" {
			if observeErr := observer.Observe(ctx, record.HostID, record.Attempt); observeErr == nil {
				if controlmodel.IsExecutionAttemptTerminal(record.Attempt.State) {
					record.PendingTerminal = &PendingTerminal{Action: "physical-stop"}
				} else if record.Attempt.State == controlmodel.ExecutionCancelRequested {
					record.PendingTerminal = &PendingTerminal{Action: "cancelled"}
				}
			}
		}
		e.deferTerminalRetry(journal, record, err)
	}
}

func (e *Engine) deferTerminalRetry(journal *Journal, record *JournalRecord, err error) {
	record.DeliveryFailures = min(16, record.DeliveryFailures+1)
	delay := min(30*time.Second, time.Second<<min(5, record.DeliveryFailures-1))
	delay += delay * time.Duration(record.Attempt.ID[0]%26) / 100
	record.NextDeliveryAt = time.Now().Add(delay).UnixMilli()
	logAttempt("terminal_delivery_failed", record.HostID, record.Attempt, record.ProviderName,
		slog.String("action", record.PendingTerminal.Action), slog.Duration("retry_after", delay), slog.Any("error", err))
	_ = saveHostJournal(journal, record)
}

func logAttempt(stage string, hostID uuid.UUID, attempt *controlmodel.ExecutionAttempt,
	providerName string, attrs ...slog.Attr) {
	if attempt == nil {
		return
	}
	base := []any{
		"stage", stage,
		"host_id", hostID,
		"run_id", attempt.RunID,
		"agent_task_id", attempt.AgentTaskID,
		"attempt_id", attempt.ID,
		"attempt", attempt.Attempt,
	}
	if providerName != "" {
		base = append(base, "provider", providerName)
	}
	for _, attr := range attrs {
		base = append(base, attr)
	}
	slog.Info("runtime execution", base...)
}

func (e *Engine) renewLoop(ctx context.Context, cancel context.CancelFunc, hostID uuid.UUID,
	execution *controlmodel.ExecutionAttempt, failures chan<- error, attemptMutex *sync.Mutex) {
	interval := e.Config.LeaseTTL / 3
	if interval < time.Second {
		interval = time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	leaseDeadline := time.Now().Add(e.Config.LeaseTTL)
	attemptMutex.Lock()
	if execution.LeaseExpiresAt != nil && execution.LeaseExpiresAt.After(time.Now()) {
		leaseDeadline = *execution.LeaseExpiresAt
	}
	attemptMutex.Unlock()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			attemptMutex.Lock()
			err := e.Client.Renew(ctx, hostID, execution, e.Config.LeaseTTL)
			snapshot := *execution
			stopped := controlmodel.IsExecutionAttemptTerminal(execution.State) || execution.State == controlmodel.ExecutionCancelRequested
			attemptMutex.Unlock()
			if err != nil {
				if !time.Now().Before(leaseDeadline) {
					logAttempt("lease_expired", hostID, &snapshot, "", slog.Any("error", err))
					cancel()
					select {
					case failures <- err:
					default:
					}
					return
				}
				logAttempt("lease_renew_failed", hostID, &snapshot, "",
					slog.Time("lease_deadline", leaseDeadline), slog.Any("error", err))
				continue
			}
			leaseDeadline = time.Now().Add(e.Config.LeaseTTL)
			if stopped {
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
