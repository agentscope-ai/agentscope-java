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
		if e.active.Load() >= capacity {
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
			e.replayPendingTerminals(ctx)
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
	logAttempt("claimed", hostID, execution, "")
	record := &JournalRecord{Attempt: execution, Task: work.Task, Context: work.Context,
		HostID: hostID, AttemptToken: work.AttemptToken}
	journal := &Journal{Root: e.Config.StateRoot}
	_ = journal.Save(record)
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
	_ = journal.Save(record)
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

	prompt, err = appendRuntimeContext(prompt, work.Context.Task, provider.Describe(adapter),
		e.Config.CollaborationMCP, e.Config.CollaborationCLI, work.TaskToken)
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
	go e.renewLoop(runCtx, cancel, hostID, execution, leaseFailures)
	providerRequest := provider.Request{
		Prompt: prompt, Workspace: path, RuntimeStateRoot: e.Config.StateRoot,
		ProviderSessionID: execution.ProviderSessionID,
		Configuration:     work.Profile.Configuration, Definition: work.Definition,
		CustomArgs:       hostedCustomArgs(work.ExecutionOverrides),
		CollaborationMCP: e.Config.CollaborationMCP, CollaborationCLI: e.Config.CollaborationCLI,
		ControlPlane: e.Config.ControlPlane, TaskToken: work.TaskToken,
		TaskID: work.Context.Task.ID.String(), IssueID: work.Context.Task.IssueID.String(),
		AgentID: work.Context.Task.AgentRef, RunID: work.Context.Task.OrchestrationRunID.String(),
		TeamID: taskTeamID(work.Context.Task),
	}
	if approver, ok := e.Client.(interface {
		AwaitToolApproval(context.Context, string, string, provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error)
	}); ok {
		providerRequest.ApproveTool = func(ctx context.Context, request provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
			return approver.AwaitToolApproval(ctx, work.Context.Task.ID.String(), work.TaskToken, request)
		}
	}
	result, runErr := adapter.Run(runCtx, providerRequest, func(event provider.Event) error {
		record.Events = append(record.Events, event)
		ordinal := int64(len(record.Events))
		if err := journal.Save(record); err != nil {
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
		if event.ProviderSessionID != "" && event.ProviderSessionID != execution.ProviderSessionID {
			checkpoint, _ := json.Marshal(map[string]string{"providerSessionId": event.ProviderSessionID})
			if err := e.Client.Checkpoint(runCtx, hostID, execution, event.ProviderSessionID, checkpoint); err != nil {
				return err
			}
		}
		return nil
	})
	if controlmodel.IsExecutionAttemptTerminal(execution.State) {
		logAttempt("terminal_observed", hostID, execution, work.Profile.Provider)
		_ = journal.Remove(execution.ID)
		return
	}
	if execution.State == controlmodel.ExecutionCancelRequested {
		record.PendingTerminal = &PendingTerminal{Action: "cancelled"}
		_ = journal.Save(record)
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
	_ = journal.Save(record)
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
		prompt += "\nThe task-scoped `agentscope` CLI is also available through your shell. " +
			"Use `agentscope task context`, `agentscope issue current`, `agentscope task progress --content-file <path>`, " +
			"`agentscope task respond --content-file <path>`, and `agentscope task run graph` as a fallback or for file-based results. " +
			"Run `agentscope task --help` for the complete task-scoped command surface."
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
	_ = journal.Save(record)
	e.deliverPendingTerminal(context.WithoutCancel(parent), journal, record)
}

func (e *Engine) replayPendingTerminals(ctx context.Context) {
	j := &Journal{Root: e.Config.StateRoot}
	records, err := j.List()
	if err != nil {
		return
	}
	for _, record := range records {
		if record.PendingTerminal == nil || record.HostID == uuid.Nil {
			continue
		}
		if restorer, ok := e.Client.(interface {
			RestoreAttemptToken(uuid.UUID, string)
		}); ok {
			restorer.RestoreAttemptToken(record.Attempt.ID, record.AttemptToken)
		}
		e.deliverPendingTerminal(ctx, j, record)
	}
}

func (e *Engine) deliverPendingTerminal(ctx context.Context, journal *Journal, record *JournalRecord) {
	if record == nil || record.Attempt == nil || record.PendingTerminal == nil {
		return
	}
	terminal := record.PendingTerminal
	var err error
	switch terminal.Action {
	case "complete":
		err = e.Client.Complete(ctx, record.HostID, record.Attempt, terminal.Result, terminal.Checkpoint)
	case "fail":
		err = e.Client.Fail(ctx, record.HostID, record.Attempt, terminal.FailureCode,
			terminal.FailureMessage, terminal.Checkpoint)
	case "cancelled":
		err = e.Client.Cancelled(ctx, record.HostID, record.Attempt)
	default:
		return
	}
	if err == nil {
		logAttempt("terminal_delivered", record.HostID, record.Attempt, "", slog.String("action", terminal.Action))
		_ = journal.Remove(record.Attempt.ID)
	} else {
		logAttempt("terminal_delivery_failed", record.HostID, record.Attempt, "",
			slog.String("action", terminal.Action), slog.Any("error", err))
	}
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
	execution *controlmodel.ExecutionAttempt, failures chan<- error) {
	interval := e.Config.LeaseTTL / 3
	if interval < time.Second {
		interval = time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	leaseDeadline := time.Now().Add(e.Config.LeaseTTL)
	if execution.LeaseExpiresAt != nil && execution.LeaseExpiresAt.After(time.Now()) {
		leaseDeadline = *execution.LeaseExpiresAt
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := e.Client.Renew(ctx, hostID, execution, e.Config.LeaseTTL); err != nil {
				if !time.Now().Before(leaseDeadline) {
					logAttempt("lease_expired", hostID, execution, "", slog.Any("error", err))
					cancel()
					select {
					case failures <- err:
					default:
					}
					return
				}
				logAttempt("lease_renew_failed", hostID, execution, "",
					slog.Time("lease_deadline", leaseDeadline), slog.Any("error", err))
				continue
			}
			leaseDeadline = time.Now().Add(e.Config.LeaseTTL)
			if controlmodel.IsExecutionAttemptTerminal(execution.State) {
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
