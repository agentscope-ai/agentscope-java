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

package prober

import (
	"context"
	"errors"
)

// ErrNotFoundOnDataPlane is returned when the data plane answers 404 for a
// session-scoped query (unknown session on the live instance).
var ErrNotFoundOnDataPlane = errors.New("prober: not found on data plane")

// MaxSessionsProbePage is the defensive upper bound for GET /agentscope/sessions.
// When a probe returns this many (or more) sessions, ArchiveMissing must be
// skipped — the list may be silently truncated and omitted sessions are not
// actually gone from the data plane.
const MaxSessionsProbePage = 500

// SessionsProbeLikelyTruncated reports whether a sessions probe page size
// suggests silent truncation.
func SessionsProbeLikelyTruncated(n int) bool {
	return n >= MaxSessionsProbePage
}

// SessionsProbeResult is an extended sessions probe response with truncation hints.
type SessionsProbeResult struct {
	Sessions  []SessionSnapshot
	Truncated bool
	HasMore   bool
}

// LikelyTruncated is true when the DP signaled truncation/hasMore or the
// page size hit MaxSessionsProbePage.
func (r SessionsProbeResult) LikelyTruncated() bool {
	if r.Truncated || r.HasMore {
		return true
	}
	return SessionsProbeLikelyTruncated(len(r.Sessions))
}

// DataPlaneProber encapsulates calls to the data plane contract HTTP API.
// Used by DiscoveryController for initial probing and periodic health checks.
type DataPlaneProber interface {
	// ProbeInfo calls GET /agentscope/info to get data plane metadata.
	ProbeInfo(ctx context.Context, endpoint string) (*DataPlaneInfo, error)

	// ProbeHealth calls GET /agentscope/health.
	ProbeHealth(ctx context.Context, endpoint string) (bool, error)

	// ProbeSessions calls GET /agentscope/sessions (Level 2+).
	ProbeSessions(ctx context.Context, endpoint string) ([]SessionSnapshot, error)

	// SendCompress calls POST /agentscope/sessions/{id}/compress (Level 3+).
	SendCompress(ctx context.Context, endpoint string, sessionID string) error

	// SendTerminate calls POST /agentscope/sessions/{id}/terminate (Level 3+).
	SendTerminate(ctx context.Context, endpoint string, sessionID string) error

	// FetchSessionState calls GET /agentscope/sessions/{id}/state (Level 2+).
	FetchSessionState(ctx context.Context, endpoint string, sessionID string) (*SessionState, error)

	// FetchContext calls GET /agentscope/sessions/{id}/context (capability: context-query).
	FetchContext(ctx context.Context, endpoint string, sessionID string) (*ContextSnapshot, error)

	// FetchMessages calls GET /agentscope/sessions/{id}/messages (capability: message-query).
	FetchMessages(ctx context.Context, endpoint string, sessionID string, offset, limit int) (*MessagePage, error)

	// FetchSubagents calls GET /agentscope/subagents (capability: subagent-inventory).
	FetchSubagents(ctx context.Context, endpoint string) ([]SubagentInfo, error)

	// FetchWorkspaces calls GET /agentscope/workspaces (capability: workspace-inventory).
	FetchWorkspaces(ctx context.Context, endpoint string) ([]WorkspaceInfo, error)

	// FetchTasks calls GET /agentscope/sessions/{id}/tasks (capability: task-query).
	FetchTasks(ctx context.Context, endpoint string, sessionID string) ([]TaskInfo, error)

	// FetchSubagentTasks calls GET /agentscope/sessions/{id}/subagent-tasks.
	FetchSubagentTasks(ctx context.Context, endpoint string, sessionID string) ([]SubagentTaskInfo, error)

	// CancelSubagentTask calls DELETE /agentscope/sessions/{id}/subagent-tasks/{taskId}.
	CancelSubagentTask(ctx context.Context, endpoint string, sessionID, taskID string) error

	// SendPlanMode calls POST /agentscope/sessions/{id}/plan-mode with {"active":bool}.
	SendPlanMode(ctx context.Context, endpoint string, sessionID string, active bool) error
}
