package prober

import (
	"context"
	"errors"
)

// ErrNotFoundOnDataPlane is returned when the data plane answers 404 for a
// session-scoped query (unknown session on the live instance).
var ErrNotFoundOnDataPlane = errors.New("prober: not found on data plane")

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
}
