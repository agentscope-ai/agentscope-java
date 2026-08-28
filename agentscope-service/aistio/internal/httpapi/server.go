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

package httpapi

import (
	"context"
	"crypto/rand"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	authv1 "k8s.io/api/authentication/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/spring-ai-alibaba/aistio/internal/artifact"
	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/features"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/realtime"
	"github.com/spring-ai-alibaba/aistio/internal/runtimebinding"
	"github.com/spring-ai-alibaba/aistio/internal/scheduler"
	"github.com/spring-ai-alibaba/aistio/internal/sessionops"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/taskauth"
	"github.com/spring-ai-alibaba/aistio/internal/taskplane"
	"github.com/spring-ai-alibaba/aistio/internal/version"
	"github.com/spring-ai-alibaba/aistio/internal/worksource"
)

// SessionCommandSender dispatches a session command (compress/terminate)
// over a live ASDP stream. Implemented by asdp.Distributor.
type SessionCommandSender interface {
	SendSessionCommand(tenant, namespace, instanceID, sessionID, command string) error
}

// InventoryProvider exposes the latest per-instance inventory reports held
// by the ASDP connection registry. Implemented by asdp.Server.
type InventoryProvider interface {
	GetInventoriesForAgent(tenant, namespace, agentName string) []*asdp.InstanceInventory
}

// ServerOptions configures the REST API server.
type ServerOptions struct {
	Client       client.Client
	Store        store.Store
	Prober       prober.DataPlaneProber
	Addr         string
	Experimental bool
	// ASDPCommands, when non-nil, delivers session commands over live ASDP
	// streams before falling back to the HTTP data-plane contract.
	ASDPCommands SessionCommandSender
	// ASDPInventory, when non-nil, serves instance inventory (subagents,
	// workspaces) from the ASDP connection registry.
	ASDPInventory InventoryProvider
	// AuthToken, when non-empty, requires a matching bearer token on all
	// /api/v1 requests.
	AuthToken string
	// TLSCertFile and TLSKeyFile enable HTTPS when both are provided.
	TLSCertFile string
	TLSKeyFile  string
	// KubeClient, when non-nil, enables Kubernetes TokenReview authentication
	// for bearer tokens, taking precedence over static AuthToken.
	KubeClient kubernetes.Interface
	// Product, when non-nil, mounts the Managed Agents control plane
	// (`/api/*`, console JWT) onto the same router and port.
	Product *product.Server
	// StaticDir, when non-empty, serves the console SPA with history
	// fallback for any unmatched non-API route.
	StaticDir string
	// Registry holds self-registered data-plane instances (standalone mode).
	Registry *dataplane.Registry
	// InternalToken authenticates POST /api/v1/dataplanes/register and heartbeats.
	InternalToken string
	// TaskTokenSecret signs task-scoped credentials. All replicas must use the
	// same value; when omitted a random process-local development key is used.
	TaskTokenSecret string
	// HostedStore enables the /api/v1/dp/* hosted DistributedStore API.
	HostedStore bool
	// TranscriptMessages optionally reads Level-3 message history from a
	// control-plane transcript store (NAS / object storage). When it returns
	// ok=true, getSessionMessages skips the live DP fallback and does not
	// require the message-query capability.
	TranscriptMessages  TranscriptMessagesFunc
	Features            features.Gates
	ArtifactProvider    artifact.Provider
	CollaborationEvents *realtime.Hub
}

// Server is the REST API server for the control plane.
type Server struct {
	client              client.Client
	store               store.Store
	prober              prober.DataPlaneProber
	router              *gin.Engine
	httpServer          *http.Server
	experimental        bool
	authToken           string
	tlsCertFile         string
	tlsKeyFile          string
	kubeClient          kubernetes.Interface
	asdpCommands        SessionCommandSender
	asdpInventory       InventoryProvider
	product             *product.Server
	staticDir           string
	registry            *dataplane.Registry
	internalToken       string
	hostedStore         bool
	transcriptMessages  TranscriptMessagesFunc
	sessionOps          *sessionops.Router
	features            features.Gates
	taskPlane           *taskplane.Service
	runtimeBindings     *runtimebinding.Resolver
	taskTokens          taskauth.Manager
	artifactProvider    artifact.Provider
	collaborationEvents *realtime.Hub
	workSources         *worksource.Service
}

// NewServer creates a new API server.
func NewServer(opts ServerOptions) *Server {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(gin.Logger())

	s := &Server{
		client:              opts.Client,
		store:               opts.Store,
		prober:              opts.Prober,
		router:              router,
		experimental:        opts.Experimental,
		authToken:           opts.AuthToken,
		tlsCertFile:         opts.TLSCertFile,
		tlsKeyFile:          opts.TLSKeyFile,
		kubeClient:          opts.KubeClient,
		asdpCommands:        opts.ASDPCommands,
		asdpInventory:       opts.ASDPInventory,
		product:             opts.Product,
		staticDir:           opts.StaticDir,
		registry:            opts.Registry,
		internalToken:       opts.InternalToken,
		hostedStore:         opts.HostedStore,
		features:            opts.Features,
		transcriptMessages:  opts.TranscriptMessages,
		artifactProvider:    opts.ArtifactProvider,
		collaborationEvents: opts.CollaborationEvents,
		httpServer: &http.Server{
			Addr:         opts.Addr,
			Handler:      router,
			ReadTimeout:  30 * time.Second,
			WriteTimeout: 30 * time.Second,
		},
	}

	if s.transcriptMessages == nil {
		if root := strings.TrimSpace(os.Getenv("AISTIO_TRANSCRIPT_FS_ROOT")); root != "" {
			s.transcriptMessages = FilesystemTranscriptMessages(root)
		}
	}

	if opts.Store != nil && opts.Registry != nil {
		s.sessionOps = sessionops.NewRouter(opts.Registry, opts.Store, opts.Prober, opts.ASDPCommands)
		s.sessionOps.InternalToken = opts.InternalToken
	}
	if opts.Store != nil {
		adapters := worksource.NewRegistry()
		adapters.Register("github", &worksource.GitHubAdapter{Store: opts.Store})
		s.workSources = &worksource.Service{Store: opts.Store, Adapters: adapters}
		secret := []byte(opts.TaskTokenSecret)
		if len(secret) < 32 {
			secret = make([]byte, 32)
			if _, err := rand.Read(secret); err != nil {
				panic(fmt.Sprintf("generate task token secret: %v", err))
			}
			ctrl.Log.WithName("httpapi").Info("using a process-local task token key; configure TaskTokenSecret for multi-replica deployments")
		}
		s.taskTokens = taskauth.Manager{Secret: secret, TTL: time.Hour}
		s.taskPlane = &taskplane.Service{Store: opts.Store}
		var external runtimebinding.ExternalCommander
		if commander, ok := opts.ASDPCommands.(runtimebinding.ExternalCommander); ok {
			external = commander
		}
		s.runtimeBindings = &runtimebinding.Resolver{Store: opts.Store, Tasks: s.taskPlane,
			Managed: opts.Product, External: external, Tokens: &s.taskTokens}
		s.taskPlane.CancelBackend = s.runtimeBindings.CancelAttempt
	}

	if opts.KubeClient == nil {
		ctrl.Log.WithName("httpapi").Info("authorization disabled: no kube client configured (static token mode does not support authorization)")
	}

	s.registerRoutes()
	return s
}

// SessionOps returns the session command router, or nil when store/registry
// are unavailable.
func (s *Server) SessionOps() *sessionops.Router {
	return s.sessionOps
}

// DispatchAgentTask is the durable-outbox adapter for the single runtime
// binding resolver owned by this server. HTTP and background delivery execute
// the exact same Managed/External/Hosted state machine.
func (s *Server) DispatchAgentTask(ctx context.Context, taskID uuid.UUID) error {
	if s.runtimeBindings == nil {
		return fmt.Errorf("runtime binding resolver is unavailable")
	}
	_, err := (&scheduler.Scheduler{Store: s.store, Resolver: s.runtimeBindings}).DispatchTask(ctx, taskID)
	return err
}

func (s *Server) registerRoutes() {
	// System
	s.router.GET("/healthz", s.healthz)
	s.router.GET("/readyz", s.readyz)
	s.router.GET("/actuator/health", s.healthz)
	s.router.GET("/api/v1/version", s.version)
	// External application registration owns its machine-identity trust
	// boundary. The handler accepts either the bootstrap identity for first
	// registration or an Agent registration credential for later instances.
	if s.store != nil {
		s.router.POST("/api/v1/agent-registrations", s.registerExternalAgent)
		s.router.POST("/api/v1/work-sources/:workSourceId/webhooks/github", s.githubWorkSourceWebhook)
		s.router.POST("/invoke/v1/endpoints/:slug/conversations", s.invokeEndpointConversation)
		s.router.POST("/invoke/v1/endpoints/:slug/jobs", s.invokeEndpointJob)
		s.router.GET("/invoke/v1/jobs/:issueId", s.getEndpointJob)
		s.router.GET("/invoke/v1/jobs/:issueId/events", s.getEndpointJobEvents)
	}

	// Managed Agents control plane. Mounted on an unprefixed group so its
	// JWT/internal-token chain applies only to the product routes.
	if s.product != nil {
		pg := s.router.Group("")
		pg.Use(s.product.Middlewares()...)
		s.product.Register(pg)
	}

	v1 := s.router.Group("/api/v1")
	v1.Use(s.authMiddleware())
	v1.Use(s.workspaceRBACMiddleware())
	v1.Use(s.authzMiddleware())
	{
		v1.GET("/me/navigation", s.navigationAccess)
		// Fleet overview + token metrics (store-backed).
		if s.store != nil {
			v1.POST("/issues/:issueId/team-proposals", s.createTeamProposal)
			v1.POST("/issues/:issueId/team-proposals/:proposalId/confirm", s.confirmTeamProposal)
			v1.GET("/agent-endpoints", s.listAgentEndpoints)
			v1.POST("/agent-endpoints", s.createAgentEndpoint)
			v1.GET("/agent-endpoints/:endpointId", s.getAgentEndpoint)
			v1.PATCH("/agent-endpoints/:endpointId", s.patchAgentEndpoint)
			v1.GET("/work-sources", s.listWorkSources)
			v1.POST("/work-sources", s.createWorkSource)
			v1.GET("/work-sources/:workSourceId", s.getWorkSource)
			v1.PATCH("/work-sources/:workSourceId", s.patchWorkSource)
			v1.GET("/overview", s.fleetOverview)
			v1.GET("/overview/timeseries", s.overviewTimeseries)
			v1.GET("/metrics/tokens", s.queryTokenMetrics)
			v1.GET("/metrics/agents", s.queryAgentMetrics)
			v1.GET("/agent-instances", s.listAgentInstances)
			v1.GET("/agent-instances/:instanceId", s.getAgentInstance)
			v1.POST("/runtime-profiles", s.upsertRuntimeProfile)
			v1.GET("/runtime-profiles", s.listRuntimeProfiles)
			v1.GET("/runtime-profiles/:name", s.getRuntimeProfile)
			v1.PUT("/runtime-profiles/:name", s.upsertRuntimeProfile)
			v1.POST("/runtime-pools", s.upsertRuntimePool)
			v1.GET("/runtime-pools", s.listRuntimePools)
			v1.GET("/runtime-pools/:name", s.getRuntimePool)
			v1.PUT("/runtime-pools/:name", s.upsertRuntimePool)
			v1.GET("/runtime-hosts", s.listRuntimeHosts)
			v1.GET("/runtime-hosts/:hostId", s.getRuntimeHost)

			agents := v1.Group("/agents")
			agents.GET("", s.listCatalogAgents)
			agents.POST("", s.createCatalogAgent)
			agents.GET("/:agentId", s.getCatalogAgent)
			agents.PATCH("/:agentId", s.patchCatalogAgent)
			agents.GET("/:agentId/definition", s.getManagedAgentDefinition)
			agents.GET("/:agentId/versions", s.listManagedAgentVersions)
			agents.GET("/:agentId/bindings", s.listAgentBindings)
			agents.POST("/:agentId/bindings", s.createAgentBinding)
			agents.PATCH("/:agentId/bindings/:bindingId", s.patchAgentBinding)
			agents.GET("/:agentId/instances", s.listCatalogAgentInstances)
			v1.POST("/agent-registrations/:agentId/credentials/rotate", s.rotateAgentRegistrationCredential)
			v1.DELETE("/agent-registrations/:agentId/credentials/:credentialId", s.revokeAgentRegistrationCredential)
		}

		// Data-plane self-registration (internal token). Listed for console
		// under JWT auth; mutations use a separate group below.
		v1.GET("/dataplanes", s.listDataPlanes)

		// Agent lifecycle. CRD-backed when Kubernetes is available; otherwise
		// serve summaries from the self-registration registry.
		if s.store == nil && s.client != nil {
			agents := v1.Group("/agents")
			{
				agents.GET("", s.listAgents)
				agents.GET("/:name", s.getAgent)
				agents.POST("/:name/push", s.pushAgent)
				agents.PATCH("/:name", s.patchAgent)
				agents.DELETE("/:name", s.deleteAgent)
				agents.GET("/:name/health", s.agentHealth)
				agents.GET("/:name/revisions", s.listRevisions)
				agents.GET("/:name/revisions/:rev", s.getRevision)
				agents.POST("/:name/rollback", s.rollbackAgent)
				agents.POST("/:name/adopt", s.adoptAgent)
				agents.GET("/:name/subagents", s.listAgentSubagents)
				agents.GET("/:name/workspaces", s.listAgentWorkspaces)
			}
		} else if s.store == nil {
			agents := v1.Group("/agents")
			{
				agents.GET("", s.listAgentsFromRegistry)
				agents.GET("/:name", s.getAgentFromRegistry)
				agents.GET("/:name/subagents", s.listAgentSubagents)
				agents.GET("/:name/workspaces", s.listAgentWorkspaces)
			}
		}

		// Sessions (store-backed, flat top-level resource)
		if s.store != nil {
			sessions := v1.Group("/sessions")
			{
				sessions.GET("", s.listSessions)
				sessions.GET("/:sessionId", s.getSession)
				sessions.GET("/:sessionId/context", s.getSessionContext)
				sessions.GET("/:sessionId/events", s.getSessionEvents)
				sessions.GET("/:sessionId/messages", s.getSessionMessages)
				sessions.GET("/:sessionId/tasks", s.getSessionTasks)
				sessions.GET("/:sessionId/subagent-tasks", s.getSessionSubagentTasks)
				sessions.DELETE("/:sessionId/subagent-tasks/:taskId", s.cancelSessionSubagentTask)
				sessions.POST("/:sessionId/plan-mode", s.postSessionPlanMode)
				sessions.POST("/:sessionId/user-message", s.postSessionUserMessage)
				sessions.GET("/:sessionId/commands", s.listSessionCommands)
				sessions.GET("/:sessionId/turns", s.listSessionTurns)
				sessions.POST("/:sessionId/compress", s.compressSession)
				sessions.POST("/:sessionId/terminate", s.terminateSession)
				sessions.POST("/:sessionId/abort", s.abortSession)
				sessions.POST("/:sessionId/archive", s.archiveSession)
				sessions.POST("/:sessionId/restore", s.restoreSession)
				sessions.DELETE("/:sessionId", s.deleteSession)
			}
			v1.GET("/commands", s.listRecentCommands)
		}

		// ModelConfig
		if s.client != nil {
			modelconfigs := v1.Group("/modelconfigs")
			{
				modelconfigs.POST("", s.createModelConfig)
				modelconfigs.GET("", s.listModelConfigs)
				modelconfigs.GET("/:name", s.getModelConfig)
				modelconfigs.PATCH("/:name", s.patchModelConfig)
				modelconfigs.DELETE("/:name", s.deleteModelConfig)
			}

			// MCPServer
			mcpservers := v1.Group("/mcpservers")
			{
				mcpservers.POST("", s.createMCPServer)
				mcpservers.GET("", s.listMCPServers)
				mcpservers.GET("/:name", s.getMCPServer)
				mcpservers.PATCH("/:name", s.patchMCPServer)
				mcpservers.DELETE("/:name", s.deleteMCPServer)
				mcpservers.GET("/:name/tools", s.listMCPTools)
			}
		}

		if s.experimental && s.client != nil {
			sandboxes := v1.Group("/sandboxes")
			{
				sandboxes.POST("", s.createSandbox)
				sandboxes.GET("", s.listSandboxes)
				sandboxes.GET("/:name", s.getSandbox)
				sandboxes.DELETE("/:name", s.deleteSandbox)
			}
		}
	}

	// Issue collaboration is the sole coordination plane. The same endpoints
	// accept people through the normal auth chain and agents through a scoped
	// AgentTask token; authorship is resolved by handlers, never by
	// accepting an arbitrary actor from a request body.
	if s.store != nil {
		// Standard MCP endpoint for Agent-side Issue/Comment/AgentTask tools.
		// It accepts only a task-scoped token; every call is confined to the
		// token's persisted Issue and Task by the handler.
		mcp := s.router.Group("/mcp")
		mcp.Use(s.teamsAuthMiddleware())
		mcp.POST("/collaboration", s.collaborationMCP)

		collab := s.router.Group("/api/v1")
		collab.Use(s.teamsAuthMiddleware())
		collab.Use(s.collaborationTaskScopeMiddleware())
		collab.Use(s.workspaceRBACMiddleware())
		collab.Use(s.authzMiddleware())
		{
			definitions := collab.Group("/orchestration-definitions")
			definitions.POST("", s.createOrchestrationDefinition)
			definitions.GET("", s.listOrchestrationDefinitions)
			definitions.GET("/:definitionId", s.getOrchestrationDefinition)
			definitions.PATCH("/:definitionId", s.patchOrchestrationDefinition)
			definitions.POST("/:definitionId/validate", s.validateOrchestrationDefinition)
			definitions.POST("/:definitionId/publish", s.publishOrchestrationDefinition)
			definitions.GET("/:definitionId/revisions", s.listOrchestrationRevisions)
			definitions.POST("/:definitionId/runs", s.startOrchestrationRun)

			runs := collab.Group("/orchestration-runs")
			runs.GET("", s.listOrchestrationRuns)
			runs.GET("/:runId", s.getOrchestrationRun)
			runs.GET("/:runId/graph", s.getOrchestrationGraph)
			runs.GET("/:runId/events", s.listOrchestrationEvents)
			runs.POST("/:runId/pause", s.pauseOrchestrationRun)
			runs.POST("/:runId/resume", s.resumeOrchestrationRun)
			runs.POST("/:runId/cancel", s.cancelOrchestrationRun)
			runs.POST("/:runId/rerun", s.rerunOrchestrationRun)
			runs.POST("/:runId/signals/:name", s.signalOrchestrationRun)

			policies := collab.Group("/agent-runtime-policies")
			policies.GET("/:agentId", s.getAgentRuntimePolicy)
			policies.PUT("/:agentId", s.putAgentRuntimePolicy)

			attempts := collab.Group("/execution-attempts")
			attempts.GET("", s.listExecutionAttempts)
			attempts.GET("/:attemptId", s.getExecutionAttempt)

			if s.collaborationEvents != nil {
				collab.GET("/events", s.collaborationEventStream)
			}
			issues := collab.Group("/issues")
			issues.POST("", s.createIssue)
			issues.GET("", s.listIssues)
			issues.GET("/:issueId", s.getIssue)
			issues.PATCH("/:issueId", s.updateIssue)
			issues.POST("/:issueId/transition", s.transitionIssue)
			issues.POST("/:issueId/accept", s.acceptIssue)
			issues.POST("/:issueId/reject", s.rejectIssue)
			issues.POST("/:issueId/reopen", s.reopenIssue)
			issues.POST("/:issueId/archive", s.archiveIssue)
			issues.GET("/:issueId/summary", s.getIssueSummary)
			issues.GET("/:issueId/export", s.exportIssue)
			issues.POST("/:issueId/assign", s.assignIssue)
			issues.POST("/:issueId/children", s.createChildIssue)
			issues.GET("/:issueId/comments", s.listIssueComments)
			issues.POST("/:issueId/comments", s.addIssueComment)
			issues.PATCH("/:issueId/comments/:commentId", s.updateIssueComment)
			issues.DELETE("/:issueId/comments/:commentId", s.deleteIssueComment)
			issues.POST("/:issueId/comments/:commentId/resolve", s.resolveIssueComment)
			issues.POST("/:issueId/comments/preview-routing", s.previewIssueCommentRouting)
			issues.GET("/:issueId/activity", s.listIssueActivities)
			issues.GET("/:issueId/subscribers", s.listIssueSubscribers)
			issues.POST("/:issueId/subscribers", s.subscribeIssue)
			issues.DELETE("/:issueId/subscribers/:subscriberType/:subscriberRef", s.unsubscribeIssue)

			tasks := collab.Group("/agent-tasks")
			tasks.GET("", s.listAgentTasks)
			tasks.GET("/:taskId", s.getAgentTask)
			tasks.GET("/:taskId/context", s.taskTokenMiddleware(), s.getAgentTaskContext)
			tasks.POST("/:taskId/dispatch", s.dispatchAgentTask)
			tasks.POST("/:taskId/ack", s.taskTokenMiddleware(), s.acknowledgeAgentTask)
			tasks.POST("/:taskId/inputs/delivery-failed", s.taskTokenMiddleware(), s.failAgentTaskInputDelivery)
			tasks.POST("/:taskId/inputs/replay", s.replayAgentTaskInputs)
			tasks.POST("/:taskId/start", s.taskTokenMiddleware(), s.startAgentTask)
			tasks.POST("/:taskId/progress", s.taskTokenMiddleware(), s.progressAgentTask)
			tasks.POST("/:taskId/respond", s.taskTokenMiddleware(), s.respondAgentTask)
			tasks.POST("/:taskId/children", s.taskTokenMiddleware(), s.createAgentTaskChildIssue)
			tasks.POST("/:taskId/complete", s.taskTokenMiddleware(), s.completeAgentTask)
			tasks.POST("/:taskId/fail", s.taskTokenMiddleware(), s.failAgentTask)
			tasks.POST("/:taskId/cancel", s.cancelAgentTask)
			tasks.POST("/:taskId/retry", s.retryAgentTask)
			tasks.GET("/:taskId/run", s.taskTokenMiddleware(), s.getTaskRun)
			tasks.GET("/:taskId/run/graph", s.taskTokenMiddleware(), s.getTaskRunGraph)
			tasks.POST("/:taskId/run/node/complete", s.taskTokenMiddleware(), s.completeTaskRunNode)
			tasks.POST("/:taskId/run/node/fail", s.taskTokenMiddleware(), s.failTaskRunNode)
			tasks.POST("/:taskId/run/replan", s.taskTokenMiddleware(), s.replanTaskRun)
			tasks.POST("/:taskId/run/signals/:name", s.taskTokenMiddleware(), s.signalTaskRun)
			tasks.GET("/:taskId/run/artifacts", s.taskTokenMiddleware(), s.getTaskRunArtifacts)

			teams := collab.Group("/teams")
			teams.POST("", s.createCollaborationTeam)
			teams.GET("", s.listCollaborationTeams)
			teams.GET("/:teamId", s.getCollaborationTeam)
			teams.PATCH("/:teamId", s.updateCollaborationTeam)
			teams.POST("/:teamId/members", s.addCollaborationTeamMember)
			teams.DELETE("/:teamId/members/:memberId", s.removeCollaborationTeamMember)

			artifacts := collab.Group("/artifacts")
			artifacts.POST("/uploads", s.uploadArtifact)
			artifacts.GET("/:artifactId", s.getArtifact)
			artifacts.POST("/:artifactId/complete", s.completeArtifact)
			artifacts.POST("/:artifactId/download", s.downloadArtifact)

			inbox := collab.Group("/inbox")
			inbox.GET("", s.listInbox)
			inbox.POST("/:inboxId/read", s.readInbox)
			inbox.POST("/:inboxId/archive", s.archiveInbox)

			approvals := collab.Group("/approvals")
			approvals.POST("", s.createApproval)
			approvals.GET("", s.listApprovals)
			approvals.GET("/:approvalId", s.getApproval)
			approvals.POST("/:approvalId/decide", s.decideApproval)

			automations := collab.Group("/automations")
			automations.POST("", s.createAutomation)
			automations.GET("", s.listAutomations)
			automations.GET("/:automationId", s.getAutomation)
			automations.PATCH("/:automationId", s.updateAutomation)
			automations.DELETE("/:automationId", s.archiveAutomation)
			automations.POST("/:automationId/trigger", s.triggerAutomation)
			automations.GET("/:automationId/runs", s.listAutomationRuns)
		}
	}

	// Data-plane self-registration: authenticated by the shared internal token
	// so workers can register without a console JWT.
	dp := s.router.Group("/api/v1/dataplanes")
	dp.Use(s.internalTokenMiddleware())
	{
		dp.POST("/:instanceId/heartbeat", s.heartbeatDataPlane)
		dp.DELETE("/:instanceId", s.deleteDataPlane)
	}

	// Hosted DistributedStore API for data-plane coordination (KV, locks,
	// snapshots, bus, async tools). Same internal-token trust boundary.
	if s.store != nil && s.hostedStore {
		dpStore := s.router.Group("/api/v1/dp")
		dpStore.Use(s.internalTokenMiddleware())
		s.registerHostedStoreRoutes(dpStore)
	}

	// Runtime Host protocol has a separate machine-to-machine trust boundary.
	// The initial HTTP transport is versioned independently from Application ASDP.
	if s.store != nil && s.features.RuntimeHost {
		hosts := s.router.Group("/api/v1/runtime-hosts")
		hosts.Use(s.internalTokenMiddleware())
		{
			hosts.POST("/register", s.registerRuntimeHost)
			hosts.POST("/:hostId/heartbeat", s.heartbeatRuntimeHost)
			hosts.POST("/:hostId/state", s.setRuntimeHostState)
			hosts.POST("/:hostId/execution-attempts/claim", s.claimExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/renew", s.renewExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/preparing", s.prepareExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/running", s.startExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/checkpoint", s.checkpointExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/complete", s.completeExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/fail", s.failExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/cancelled", s.cancelledExecutionAttempt)
		}
	}

	if s.staticDir != "" {
		s.router.NoRoute(s.spaFallback())
	}
}

// spaFallback serves the console bundle, falling back to index.html so the
// client-side router owns deep links. API paths keep returning JSON 404s.
func (s *Server) spaFallback() gin.HandlerFunc {
	fileServer := http.FileServer(http.Dir(s.staticDir))
	index := filepath.Join(s.staticDir, "index.html")
	return func(c *gin.Context) {
		if strings.HasPrefix(c.Request.URL.Path, "/api/") {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
			return
		}
		path := filepath.Join(s.staticDir, filepath.Clean(c.Request.URL.Path))
		if info, err := os.Stat(path); err == nil && !info.IsDir() {
			// Vite content-hashes /assets/* filenames, so they are safe to cache
			// immutably; everything else is revalidated so console updates land
			// without a hard refresh.
			if strings.HasPrefix(c.Request.URL.Path, "/assets/") {
				c.Header("Cache-Control", "public, max-age=31536000, immutable")
			} else {
				c.Header("Cache-Control", "no-cache")
			}
			fileServer.ServeHTTP(c.Writer, c.Request)
			return
		}
		c.Header("Cache-Control", "no-cache")
		c.File(index)
	}
}

// authMiddleware enforces authentication. A console JWT is accepted first so
// the UI can use one credential across both API surfaces. Otherwise bearer
// tokens are validated via Kubernetes TokenReview when a KubeClient is
// configured, then against the static authToken. No-op when none apply.
func (s *Server) authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		token := requestBearerToken(c)

		if s.product != nil {
			if claims, err := s.product.VerifyToken(token); err == nil {
				c.Set("userId", claims.Subject)
				c.Set("username", claims.Username)
				c.Set("groups", claims.Roles)
				c.Set(ctxConsoleAuth, true)
				c.Next()
				return
			}
		}
		// K8s TokenReview auth takes precedence over static token.
		if s.kubeClient != nil {
			s.kubeAuth(c)
			return
		}
		if s.authToken != "" {
			if token == "" || token != s.authToken {
				c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "unauthorized"})
				return
			}
			c.Next()
			return
		}
		// The console shares this listener, so a mounted product module makes
		// its JWT the minimum bar rather than leaving the API open.
		if s.product != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "unauthorized"})
			return
		}
		c.Next()
	}
}

// requestBearerToken also accepts a WebSocket subprotocol credential because
// the browser WebSocket API cannot set an Authorization header. The server
// selects only the harmless aistio.v1 protocol, so the credential is never
// reflected to the client or placed in a URL/access log.
func requestBearerToken(c *gin.Context) string {
	auth := c.GetHeader("Authorization")
	if token, found := strings.CutPrefix(auth, "Bearer "); found {
		return token
	}
	for _, protocol := range strings.Split(c.GetHeader("Sec-WebSocket-Protocol"), ",") {
		if token, found := strings.CutPrefix(strings.TrimSpace(protocol), "aistio.jwt."); found {
			return token
		}
	}
	return ""
}

// teamsAuthMiddleware accepts either the shared internal token (data plane)
// or the normal console/JWT/kube auth chain.
func (s *Server) teamsAuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if token := c.GetHeader("X-Agent-Task-Token"); token != "" {
			task, err := s.verifyActiveTaskToken(c.Request.Context(), token, uuid.Nil)
			if err != nil {
				c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: err.Error()})
				return
			}
			c.Set(ctxInternalAuth, true)
			c.Set(ctxTaskAuth, task)
			c.Next()
			return
		}
		if s.internalToken != "" {
			if tok := c.GetHeader("X-Builder-Internal-Token"); tok != "" && tok == s.internalToken {
				c.Set(ctxInternalAuth, true)
				c.Next()
				return
			}
		}
		s.authMiddleware()(c)
	}
}

func (s *Server) verifyActiveTaskToken(ctx context.Context, token string, expectedTaskID uuid.UUID) (*controlmodel.AgentTask, error) {
	claims, err := s.taskTokens.VerifyClaims(token, time.Now().UTC())
	if err != nil {
		return nil, err
	}
	if expectedTaskID != uuid.Nil && claims.TaskID != expectedTaskID {
		return nil, fmt.Errorf("task token is not scoped to this task")
	}
	task, err := s.store.Collaboration().GetAgentTask(ctx, claims.TaskID)
	if err != nil {
		return nil, fmt.Errorf("task token subject no longer exists")
	}
	if task.CurrentAttemptID == nil {
		if claims.AttemptID != uuid.Nil {
			return nil, fmt.Errorf("task token attempt is no longer active")
		}
		return task, nil
	}
	if claims.AttemptID != *task.CurrentAttemptID {
		return nil, fmt.Errorf("task token attempt is no longer active")
	}
	attempt, err := s.store.ExecutionAttempts().Get(ctx, claims.AttemptID)
	if err != nil || attempt.DispatchGeneration != claims.Generation || controlmodel.IsExecutionAttemptTerminal(attempt.State) {
		return nil, fmt.Errorf("task token generation is no longer active")
	}
	return task, nil
}

// collaborationTaskScopeMiddleware turns task tokens into object-level
// authorization. The shared data-plane token alone is deliberately not a
// collaboration identity.
func (s *Server) collaborationTaskScopeMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		internal, _ := c.Get(ctxInternalAuth)
		if internal != true {
			c.Next()
			return
		}
		value, exists := c.Get(ctxTaskAuth)
		task, _ := value.(*controlmodel.AgentTask)
		if !exists || task == nil {
			if c.FullPath() == "/api/v1/automations/:automationId/trigger" {
				c.Next()
				return
			}
			c.AbortWithStatusJSON(http.StatusForbidden, ErrorResponse{Error: "collaboration access requires a task-scoped token"})
			return
		}
		allowed := false
		switch c.FullPath() {
		case "/api/v1/agent-tasks/:taskId", "/api/v1/agent-tasks/:taskId/context",
			"/api/v1/agent-tasks/:taskId/claim", "/api/v1/agent-tasks/:taskId/ack",
			"/api/v1/agent-tasks/:taskId/inputs/delivery-failed", "/api/v1/agent-tasks/:taskId/start",
			"/api/v1/agent-tasks/:taskId/progress", "/api/v1/agent-tasks/:taskId/respond",
			"/api/v1/agent-tasks/:taskId/children", "/api/v1/agent-tasks/:taskId/complete",
			"/api/v1/agent-tasks/:taskId/fail", "/api/v1/agent-tasks/:taskId/run",
			"/api/v1/agent-tasks/:taskId/run/graph", "/api/v1/agent-tasks/:taskId/run/node/complete",
			"/api/v1/agent-tasks/:taskId/run/node/fail", "/api/v1/agent-tasks/:taskId/run/replan",
			"/api/v1/agent-tasks/:taskId/run/signals/:name", "/api/v1/agent-tasks/:taskId/run/artifacts":
			allowed = c.Param("taskId") == task.ID.String()
		case "/api/v1/issues/:issueId":
			allowed = c.Request.Method == http.MethodGet && c.Param("issueId") == task.IssueID.String()
		case "/api/v1/issues/:issueId/comments":
			allowed = (c.Request.Method == http.MethodGet || c.Request.Method == http.MethodPost) && c.Param("issueId") == task.IssueID.String()
		case "/api/v1/teams/:teamId":
			allowed = c.Request.Method == http.MethodGet && task.TeamID != nil && c.Param("teamId") == task.TeamID.String()
		case "/api/v1/approvals":
			allowed = c.Request.Method == http.MethodPost
		case "/api/v1/artifacts/uploads", "/api/v1/artifacts/:artifactId", "/api/v1/artifacts/:artifactId/complete", "/api/v1/artifacts/:artifactId/download":
			allowed = true
		}
		if !allowed {
			c.AbortWithStatusJSON(http.StatusForbidden, ErrorResponse{Error: "resource is outside the AgentTask scope"})
			return
		}
		c.Next()
	}
}

// kubeAuth validates a bearer token via the Kubernetes TokenReview API.
func (s *Server) kubeAuth(c *gin.Context) {
	token := requestBearerToken(c)
	if token == "" {
		c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "missing bearer token"})
		return
	}

	tr := &authv1.TokenReview{
		Spec: authv1.TokenReviewSpec{Token: token},
	}
	result, err := s.kubeClient.AuthenticationV1().TokenReviews().Create(
		c.Request.Context(), tr, metav1.CreateOptions{},
	)
	if err != nil || !result.Status.Authenticated {
		c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "token authentication failed"})
		return
	}

	c.Set("username", result.Status.User.Username)
	c.Set("groups", result.Status.User.Groups)
	c.Next()
}

// Start begins serving HTTP (or HTTPS when TLS cert/key are configured).
func (s *Server) Start(ctx context.Context) error {
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		s.httpServer.Shutdown(shutdownCtx)
	}()

	if s.tlsCertFile != "" && s.tlsKeyFile != "" {
		if err := s.httpServer.ListenAndServeTLS(s.tlsCertFile, s.tlsKeyFile); err != nil && err != http.ErrServerClosed {
			return fmt.Errorf("HTTPS server error: %w", err)
		}
	} else {
		if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			return fmt.Errorf("HTTP server error: %w", err)
		}
	}
	return nil
}

func (s *Server) healthz(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func (s *Server) readyz(c *gin.Context) {
	if s.store != nil {
		if err := s.store.Ping(c.Request.Context()); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "error", "error": err.Error()})
			return
		}
	}
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func (s *Server) version(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"version":      version.Version,
		"apiVersion":   version.APIVersion,
		"component":    version.Component,
		"experimental": s.experimental,
	})
}
