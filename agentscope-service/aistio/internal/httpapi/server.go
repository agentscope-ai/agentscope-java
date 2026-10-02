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
	"crypto/subtle"
	"encoding/json"
	"errors"
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
	"github.com/spring-ai-alibaba/aistio/internal/runtimeauth"
	"github.com/spring-ai-alibaba/aistio/internal/runtimebinding"
	"github.com/spring-ai-alibaba/aistio/internal/scheduler"
	"github.com/spring-ai-alibaba/aistio/internal/secretcrypto"
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
	SendSessionCommand(tenant, namespace, agentID, instanceID, sessionID, command string) error
}

type ConversationTurnSender interface {
	SendConversationTurn(tenant, namespace, instanceID string, command *asdp.ConversationTurnCommand) error
}

// ManagedToolConfirmationSender delivers one durable control-plane Approval
// decision to the managed data plane that owns the corresponding HITL ticket.
type ManagedToolConfirmationSender interface {
	PostManagedToolConfirmation(ctx context.Context, sessionID, ownerID, toolUseID string, decision product.ManagedToolConfirmationDecision) error
}

// ManagedAttemptAbortSender delivers a fully fenced old-turn interrupt. It is
// separate from the decision sender so tests and alternative data planes can
// implement either capability explicitly.
type ManagedAttemptAbortSender interface {
	PostManagedAttemptAbort(ctx context.Context, sessionID, ownerID string, abort product.ManagedAttemptAbort) error
}

// InventoryProvider exposes the latest per-instance inventory reports held
// by the ASDP connection registry. Implemented by asdp.Server.
type InventoryProvider interface {
	GetInventoriesForAgent(tenant, namespace, agentName string) []*asdp.InstanceInventory
}

// ServerOptions configures the REST API server.
type ServerOptions struct {
	AccountDirectory AccountDirectory
	Client           client.Client
	Store            store.Store
	Prober           prober.DataPlaneProber
	Addr             string
	Experimental     bool
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
	// EndpointCredentialSecret encrypts recoverable Endpoint API keys at rest.
	// All replicas must use the same stable value. It falls back to the task,
	// console, then internal secret for local compatibility.
	EndpointCredentialSecret string
	// HostedStore enables the /api/v1/dp/* hosted DistributedStore API.
	HostedStore bool
	// TranscriptMessages optionally reads Level-3 message history from a
	// control-plane transcript store (NAS / object storage). When it returns
	// ok=true, getSessionMessages skips the live DP fallback and does not
	// require the message-query capability.
	TranscriptMessages   TranscriptMessagesFunc
	Features             features.Gates
	ArtifactProvider     artifact.Provider
	CollaborationEvents  *realtime.Hub
	ManagedConfirmations ManagedToolConfirmationSender
	ManagedAttemptAborts ManagedAttemptAbortSender
	GitHubTransport      worksource.GitHubTransport
	// ScopeMode controls whether tenant/namespace are selectable by callers or
	// fixed by this deployment. Empty preserves the multi-scope library default;
	// the aistiod binary explicitly defaults product deployments to single.
	ScopeMode        string
	DefaultTenant    string
	DefaultNamespace string
}

// Server is the REST API server for the control plane.
type Server struct {
	accounts              AccountDirectory
	client                client.Client
	store                 store.Store
	prober                prober.DataPlaneProber
	router                *gin.Engine
	httpServer            *http.Server
	experimental          bool
	authToken             string
	tlsCertFile           string
	tlsKeyFile            string
	kubeClient            kubernetes.Interface
	asdpCommands          SessionCommandSender
	asdpInventory         InventoryProvider
	product               *product.Server
	staticDir             string
	registry              *dataplane.Registry
	internalToken         string
	hostedStore           bool
	transcriptMessages    TranscriptMessagesFunc
	sessionOps            *sessionops.Router
	features              features.Gates
	taskPlane             *taskplane.Service
	runtimeBindings       *runtimebinding.Resolver
	taskTokens            taskauth.Manager
	runtimeTokens         runtimeauth.Manager
	endpointCredentialKey []byte
	artifactProvider      artifact.Provider
	collaborationEvents   *realtime.Hub
	managedConfirmations  ManagedToolConfirmationSender
	managedAttemptAborts  ManagedAttemptAbortSender
	workSources           *worksource.Service
	scopeMode             string
	defaultTenant         string
	defaultNamespace      string
}

// NewServer creates a new API server.
func NewServer(opts ServerOptions) *Server {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.ContextWithFallback = true
	router.Use(gin.Recovery())
	// OAuth callbacks carry authorization codes; omit them from request access logs.
	router.Use(gin.LoggerWithConfig(gin.LoggerConfig{Skip: func(c *gin.Context) bool {
		return strings.HasPrefix(c.Request.URL.Path, "/api/oauth/mcp/callback/")
	}}))

	scopeMode := strings.ToLower(strings.TrimSpace(opts.ScopeMode))
	if scopeMode != ScopeModeSingle {
		scopeMode = ScopeModeMulti
	}
	configuredTenant := strings.TrimSpace(opts.DefaultTenant)
	if configuredTenant == "" {
		configuredTenant = "default"
	}
	configuredNamespace := strings.TrimSpace(opts.DefaultNamespace)
	if configuredNamespace == "" {
		configuredNamespace = defaultNamespace
	}
	s := &Server{
		accounts:             opts.AccountDirectory,
		client:               opts.Client,
		store:                opts.Store,
		prober:               opts.Prober,
		router:               router,
		experimental:         opts.Experimental,
		authToken:            opts.AuthToken,
		tlsCertFile:          opts.TLSCertFile,
		tlsKeyFile:           opts.TLSKeyFile,
		kubeClient:           opts.KubeClient,
		asdpCommands:         opts.ASDPCommands,
		asdpInventory:        opts.ASDPInventory,
		product:              opts.Product,
		staticDir:            opts.StaticDir,
		registry:             opts.Registry,
		internalToken:        opts.InternalToken,
		hostedStore:          opts.HostedStore,
		features:             opts.Features,
		transcriptMessages:   opts.TranscriptMessages,
		artifactProvider:     opts.ArtifactProvider,
		collaborationEvents:  opts.CollaborationEvents,
		managedConfirmations: opts.ManagedConfirmations,
		managedAttemptAborts: opts.ManagedAttemptAborts,
		scopeMode:            scopeMode,
		defaultTenant:        configuredTenant,
		defaultNamespace:     configuredNamespace,
		httpServer: &http.Server{
			Addr:         opts.Addr,
			Handler:      router,
			ReadTimeout:  30 * time.Second,
			WriteTimeout: 30 * time.Second,
		},
	}
	if s.accounts == nil && s.product != nil {
		s.accounts = s.product
	}
	if s.store != nil && s.scopeMode == ScopeModeMulti {
		if _, err := s.ensureGlobalDefaultNamespace(context.Background()); err != nil {
			ctrl.Log.WithName("httpapi").Error(err, "unable to provision global default namespace")
		}
	}
	if s.product != nil && s.store != nil {
		s.product.SetAccountDisableGuard(func(ctx context.Context, user string) error {
			items, err := s.allManagedNamespaces(ctx)
			if err != nil {
				return fmt.Errorf("Unable to check namespace ownership")
			}
			for _, n := range items {
				if n.Kind == "shared" && n.Owner == user {
					return fmt.Errorf("Transfer ownership of namespace %s before disabling this account", n.Name)
				}
			}
			return nil
		})
	}
	if s.managedConfirmations == nil && opts.Product != nil {
		s.managedConfirmations = opts.Product
	}
	if s.managedAttemptAborts == nil && opts.Product != nil {
		s.managedAttemptAborts = opts.Product
	}
	credentialMaster := strings.TrimSpace(opts.EndpointCredentialSecret)
	if credentialMaster == "" {
		credentialMaster = opts.TaskTokenSecret
	}
	if credentialMaster == "" {
		credentialMaster = opts.AuthToken
	}
	if credentialMaster == "" {
		credentialMaster = opts.InternalToken
	}
	if credentialMaster == "" {
		// This fallback is only reachable in unauthenticated local tests. A
		// durable deployment always configures one of the secrets above.
		credentialMaster = "aistio-local-endpoint-credential-key"
	}
	s.endpointCredentialKey = secretcrypto.DeriveKey(credentialMaster)

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
		githubTransport := opts.GitHubTransport
		if githubTransport == nil {
			githubTransport = &worksource.GitHubHTTPTransport{}
		}
		adapters.Register("github", &worksource.GitHubAdapter{Store: opts.Store, Transport: githubTransport})
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
		s.runtimeTokens = runtimeauth.Manager{Secret: secret, TTL: 30 * 24 * time.Hour}
		s.taskPlane = &taskplane.Service{Store: opts.Store}
		s.taskPlane.CommentSink = s.workSources.QueueComment
		var external runtimebinding.ExternalCommander
		if commander, ok := opts.ASDPCommands.(runtimebinding.ExternalCommander); ok {
			external = commander
		}
		s.runtimeBindings = &runtimebinding.Resolver{Store: opts.Store, Tasks: s.taskPlane, AuthorizeTask: s.authorizeTaskResources,
			Managed: opts.Product, External: external, Tokens: &s.taskTokens}
		s.taskPlane.CancelBackend = s.runtimeBindings.CancelAttempt
		if s.product != nil {
			s.configureChannelWork()
			s.taskPlane.ResolveDefinition = func(ctx context.Context, agentID uuid.UUID) (json.RawMessage, error) {
				agent, err := s.store.AgentCatalog().GetAgent(ctx, agentID)
				if err != nil {
					return nil, err
				}
				definition, err := s.product.RuntimeDefinition(ctx, agent.OwnerRef, agent.ID.String())
				if errors.Is(err, product.ErrManagedDefinitionNotFound) {
					return nil, nil
				}
				if err != nil {
					return nil, err
				}
				return json.Marshal(definition)
			}
			s.product.SetManagedExecutionContextLookup(s.managedExecutionContextForSession)
			s.product.SetManagedRuntimeFenceValidator(s.validateManagedSessionRuntimeFence)
		}
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
		if s.features.RuntimeHost {
			s.router.POST("/api/v1/runtime-host-enrollments/exchange", s.exchangeRuntimeHostEnrollment)
		}
		s.router.POST("/api/v1/work-sources/:workSourceId/webhooks/github", s.githubWorkSourceWebhook)
		s.router.POST("/hooks/v1/automations/:automationId/:triggerId", s.automationWebhook)
		s.router.POST("/invoke/v1/endpoints/:slug/conversations", s.invokeEndpointConversation)
		s.router.POST("/invoke/v1/conversations/:conversationId/turns", s.continueEndpointConversation)
		s.router.GET("/invoke/v1/conversations/:conversationId", s.getEndpointConversation)
		s.router.GET("/invoke/v1/conversations/:conversationId/events", s.getEndpointConversationEvents)
		s.router.POST("/invoke/v1/endpoints/:slug/jobs", s.invokeEndpointJob)
		s.router.GET("/invoke/v1/jobs/:invocationId", s.getEndpointJob)
		s.router.GET("/invoke/v1/jobs/:invocationId/events", s.getEndpointJobEvents)
		s.router.GET("/invoke/v1/jobs/:invocationId/artifacts", s.getEndpointJobArtifacts)
		s.router.GET("/invoke/v1/jobs/:invocationId/artifacts/:artifactId", s.downloadEndpointJobArtifact)
		s.router.POST("/invoke/v1/jobs/:invocationId/cancel", s.cancelEndpointJob)
	}

	// Managed Agents control plane. Mounted on an unprefixed group so its
	// JWT/internal-token chain applies only to the product routes.
	if s.product != nil {
		pg := s.router.Group("")
		pg.Use(s.product.Middlewares()...)
		pg.Use(s.productNamespaceMiddleware())
		pg.Use(s.scopeMiddleware())
		s.product.Register(pg)
	}
	if s.store != nil {
		managedRuntime := s.router.Group("/api/internal/runtime-sessions")
		managedRuntime.Use(s.internalTokenMiddleware())
		managedRuntime.POST("/:sessionId/events", s.reportManagedSessionEvent)
		managedRuntime.POST("/:sessionId/heartbeat", s.heartbeatManagedSession)
	}

	v1 := s.router.Group("/api/v1")
	v1.Use(s.authMiddleware())
	v1.Use(s.scopeMiddleware())
	v1.Use(s.namespaceAccessMiddleware())
	v1.Use(s.workspaceRBACMiddleware())
	v1.Use(s.authzMiddleware())
	{
		v1.GET("/me/navigation", s.navigationAccess)
		v1.GET("/me/scope", s.getCurrentScope)
		if s.store != nil {
			s.registerAccessManagement(v1)
			v1.GET("/me/namespaces", s.listMyNamespaces)
			v1.POST("/namespaces", s.createNamespace)
			v1.GET("/namespaces/:namespaceName", s.getNamespaceAccess)
			v1.PUT("/namespaces/:namespaceName", s.updateNamespaceAccess)
		}
		// Fleet overview + token metrics (store-backed).
		if s.store != nil {
			v1.POST("/entity-identities:resolve", s.resolveEntityIdentities)
			v1.POST("/runtime-host-enrollments", s.createRuntimeHostEnrollment)
			v1.POST("/runtime-host-enrollment-tokens", s.createRuntimeHostEnrollmentToken)
			v1.GET("/chats", s.listChats)
			v1.POST("/chats", s.createChat)
			v1.GET("/chat-agents", s.listChatAgents)
			v1.GET("/chats/:chatId", s.getChat)
			v1.PATCH("/chats/:chatId", s.patchChat)
			v1.DELETE("/chats/:chatId", s.deleteChat)
			v1.POST("/chats/:chatId/turns", s.sendChatTurn)
			v1.POST("/issues/:issueId/team-proposals", s.createTeamProposal)
			v1.POST("/issues/:issueId/team-proposals/:proposalId/confirm", s.confirmTeamProposal)
			v1.GET("/endpoints", s.listEndpoints)
			v1.POST("/endpoints", s.createEndpoint)
			v1.GET("/endpoints/:endpointId", s.getEndpoint)
			v1.PATCH("/endpoints/:endpointId", s.patchEndpoint)
			v1.DELETE("/endpoints/:endpointId", s.archiveEndpoint)
			v1.GET("/endpoints/:endpointId/readiness", s.getEndpointReadiness)
			v1.GET("/endpoints/:endpointId/invocations", s.listEndpointInvocations)
			v1.POST("/endpoints/:endpointId/publish", s.publishEndpoint)
			v1.POST("/endpoints/:endpointId/disable", s.disableEndpoint)
			v1.GET("/endpoints/:endpointId/releases", s.listEndpointReleases)
			v1.POST("/endpoints/:endpointId/releases", s.deployEndpointRelease)
			v1.POST("/endpoints/:endpointId/releases/:releaseId/rollback", s.rollbackEndpointRelease)
			v1.GET("/endpoints/:endpointId/credentials", s.listEndpointCredentials)
			v1.POST("/endpoints/:endpointId/credentials", s.createEndpointCredential)
			v1.POST("/endpoints/:endpointId/credentials/:credentialId/rotate", s.rotateEndpointCredential)
			v1.POST("/endpoints/:endpointId/credentials/:credentialId/reveal", s.revealEndpointCredential)
			v1.DELETE("/endpoints/:endpointId/credentials/:credentialId", s.revokeEndpointCredential)
			v1.GET("/work-sources", s.listWorkSources)
			v1.POST("/work-sources", s.createWorkSource)
			v1.GET("/work-sources/:workSourceId", s.getWorkSource)
			v1.PATCH("/work-sources/:workSourceId", s.patchWorkSource)
			v1.POST("/work-sources/:workSourceId/reconcile", s.reconcileWorkSource)
			v1.POST("/work-sources/comment-outbox/flush", s.flushWorkSourceCommentOutbox)
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
			v1.PATCH("/runtime-hosts/:hostId/capacity", s.updateRuntimeHostCapacity)
			v1.POST("/runtime-hosts/:hostId/drain", s.drainRuntimeHost)
			v1.POST("/runtime-hosts/:hostId/resume", s.resumeRuntimeHost)
			v1.POST("/runtime-bindings/:bindingId/disable", s.disableRuntimeBinding)
			v1.POST("/runtime-bindings/:bindingId/enable", s.enableRuntimeBinding)
			v1.GET("/dead-letters", s.listOutboxDeadLetters)
			v1.POST("/dead-letters/:eventId/replay", s.replayOutboxDeadLetter)

			agents := v1.Group("/agents")
			agents.GET("", s.listCatalogAgents)
			agents.POST("", s.createCatalogAgent)
			agents.GET("/runtime-options", s.listAgentRuntimeOptions)
			agents.GET("/:agentId", s.getCatalogAgent)
			agents.PATCH("/:agentId", s.patchCatalogAgent)
			agents.GET("/:agentId/definition", s.getManagedAgentDefinition)
			agents.GET("/:agentId/workspace-capabilities", s.agentWorkspaceCapabilities)
			agents.PATCH("/:agentId/definition", s.patchManagedAgentDefinition)
			agents.POST("/:agentId/definition", s.initializePortableDefinition)
			agents.GET("/:agentId/versions", s.listManagedAgentVersions)
			agents.GET("/:agentId/versions/:version", s.getManagedAgentVersion)
			agents.GET("/:agentId/bindings", s.listAgentBindings)
			agents.POST("/:agentId/bindings", s.createAgentBinding)
			agents.PATCH("/:agentId/bindings/:bindingId", s.patchAgentBinding)
			agents.GET("/:agentId/hosted-settings", s.getHostedAgentSettings)
			agents.PATCH("/:agentId/hosted-settings", s.patchHostedAgentSettings)
			agents.GET("/:agentId/instances", s.listCatalogAgentInstances)
			agents.GET("/:agentId/overview", s.getAgentDetailOverview)
			agents.GET("/:agentId/runtime-inventory", s.getAgentRuntimeInventory)
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
				sessions.GET("/:sessionId/events/stream", s.streamSessionEvents)
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
		// This MCP server uses stateless POST responses, not a server SSE stream.
		// Do not let the SPA fallback answer the SDK's optional GET with HTML.
		mcp.GET("/collaboration", func(c *gin.Context) {
			c.Header("Allow", "POST")
			c.Status(http.StatusMethodNotAllowed)
		})

		collab := s.router.Group("/api/v1")
		collab.Use(s.teamsAuthMiddleware())
		collab.Use(s.collaborationTaskScopeMiddleware())
		collab.Use(s.namespaceAccessMiddleware())
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
			issues.PUT("/:issueId/access", s.updateIssueAccess)
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
			issues.GET("/:issueId/artifacts", s.listIssueArtifacts)
			issues.GET("/:issueId/subscribers", s.listIssueSubscribers)
			issues.POST("/:issueId/subscribers", s.subscribeIssue)
			issues.DELETE("/:issueId/subscribers/:subscriberType/:subscriberRef", s.unsubscribeIssue)

			tasks := collab.Group("/agent-tasks")
			tasks.GET("", s.listAgentTasks)
			tasks.GET("/:taskId", s.getAgentTask)
			tasks.GET("/:taskId/context", s.taskTokenMiddleware(), s.getAgentTaskContext)
			tasks.POST("/:taskId/workspace-application", s.taskTokenMiddleware(), s.reportWorkspaceApplication)
			tasks.POST("/:taskId/dispatch", s.dispatchAgentTask)
			tasks.POST("/:taskId/ack", s.taskTokenMiddleware(), s.acknowledgeAgentTask)
			tasks.POST("/:taskId/inputs/delivery-failed", s.taskTokenMiddleware(), s.failAgentTaskInputDelivery)
			tasks.POST("/:taskId/inputs/replay", s.replayAgentTaskInputs)
			tasks.POST("/:taskId/start", s.taskTokenMiddleware(), s.startAgentTask)
			tasks.POST("/:taskId/progress", s.taskTokenMiddleware(), s.progressAgentTask)
			tasks.POST("/:taskId/runtime-approvals", s.taskTokenMiddleware(), s.requestRuntimeToolApproval)
			tasks.GET("/:taskId/runtime-approvals/:approvalId/decision", s.taskTokenMiddleware(), s.getRuntimeToolApprovalDecision)
			tasks.POST("/:taskId/runtime-approvals/:approvalId/ack", s.taskTokenMiddleware(), s.acknowledgeRuntimeToolApprovalDecision)
			tasks.POST("/:taskId/respond", s.taskTokenMiddleware(), s.respondAgentTask)
			tasks.POST("/:taskId/children", s.taskTokenMiddleware(), s.createAgentTaskChildIssue)
			tasks.POST("/:taskId/complete", s.taskTokenMiddleware(), s.completeAgentTask)
			tasks.POST("/:taskId/fail", s.taskTokenMiddleware(), s.failAgentTask)
			tasks.POST("/:taskId/cancel", s.cancelAgentTask)
			tasks.POST("/:taskId/retry", s.retryAgentTask)
			tasks.GET("/:taskId/run", s.taskTokenMiddleware(), s.getTaskRun)
			tasks.GET("/:taskId/run/graph", s.taskTokenMiddleware(), s.getTaskRunGraph)
			tasks.POST("/:taskId/run/node/complete", s.coordinatorTaskTokenMiddleware(), s.completeTaskRunNode)
			tasks.POST("/:taskId/run/node/fail", s.coordinatorTaskTokenMiddleware(), s.failTaskRunNode)
			tasks.POST("/:taskId/run/replan", s.taskTokenMiddleware(), s.replanTaskRun)
			tasks.POST("/:taskId/run/signals/:name", s.taskTokenMiddleware(), s.signalTaskRun)
			tasks.GET("/:taskId/run/artifacts", s.taskTokenMiddleware(), s.getTaskRunArtifacts)

			teams := collab.Group("/teams")
			teams.POST("", s.createCollaborationTeam)
			teams.POST("/from-proposal/:proposalId", s.saveTeamProposalAsTeam)
			teams.GET("", s.listCollaborationTeams)
			teams.GET("/:teamId", s.getCollaborationTeam)
			teams.GET("/:teamId/overview", s.getCollaborationTeamOverview)
			teams.PATCH("/:teamId", s.updateCollaborationTeam)
			teams.POST("/:teamId/members", s.addCollaborationTeamMember)
			teams.PATCH("/:teamId/members/:memberId", s.updateCollaborationTeamMember)
			teams.DELETE("/:teamId/members/:memberId", s.removeCollaborationTeamMember)

			artifacts := collab.Group("/artifacts")
			artifacts.POST("/uploads", s.uploadArtifact)
			artifacts.GET("/:artifactId", s.getArtifact)
			artifacts.POST("/:artifactId/complete", s.completeArtifact)
			artifacts.POST("/:artifactId/download", s.downloadArtifact)

			inbox := collab.Group("/inbox")
			inbox.GET("", s.listInbox)
			inbox.GET("/summary", s.inboxSummary)
			inbox.GET("/:inboxId", s.getInbox)
			inbox.POST("/:inboxId/read", s.readInbox)
			inbox.POST("/:inboxId/archive", s.archiveInbox)

			approvals := collab.Group("/approvals")
			approvals.POST("", s.createApproval)
			approvals.GET("", s.listApprovals)
			approvals.GET("/:approvalId", s.getApproval)
			approvals.POST("/:approvalId/decide", s.decideApproval)

			automations := collab.Group("/automations")
			automations.POST("", s.createAutomation)
			automations.POST("/schedule-preview", s.previewAutomationSchedule)
			automations.GET("", s.listAutomations)
			automations.GET("/:automationId", s.getAutomation)
			automations.PATCH("/:automationId", s.updateAutomation)
			automations.DELETE("/:automationId", s.archiveAutomation)
			automations.POST("/:automationId/trigger", s.triggerAutomation)
			automations.GET("/:automationId/runs", s.listAutomationRuns)
			automations.GET("/:automationId/runs/:automationRunId", s.getAutomationRun)
			automations.POST("/:automationId/runs/:automationRunId/cancel", s.cancelAutomationRun)
			automations.POST("/:automationId/runs/:automationRunId/rerun", s.rerunAutomationRun)
			automations.POST("/:automationId/rotate-secret", s.rotateAutomationSecret)
			automations.GET("/:automationId/deliveries", s.listAutomationDeliveries)
			automations.POST("/:automationId/deliveries/:deliveryId/replay", s.replayAutomationDelivery)
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
		hosts.Use(s.runtimeHostCredentialMiddleware())
		{
			hosts.POST("/register", s.registerRuntimeHost)
			hosts.POST("/:hostId/heartbeat", s.heartbeatRuntimeHost)
			hosts.POST("/:hostId/state", s.setRuntimeHostState)
			hosts.POST("/:hostId/execution-attempts/claim", s.claimExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/renew", s.renewExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/preparing", s.prepareExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/running", s.startExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/checkpoint", s.checkpointExecutionAttempt)
			hosts.POST("/:hostId/execution-attempts/:attemptId/events", s.appendExecutionAttemptEvent)
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
			if claims, err := s.product.VerifyAccountToken(c.Request.Context(), token); err == nil {
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

// validPlatformToken authenticates public Gateway requests against the same
// configured identity sources as the management API, without running Gin
// middleware or granting management-route authorization.
func (s *Server) validPlatformToken(ctx context.Context, token string) bool {
	_, ok := s.platformPrincipal(ctx, token)
	return ok
}

func (s *Server) platformPrincipal(ctx context.Context, token string) (string, bool) {
	if token == "" {
		return "", false
	}
	if s.product != nil {
		if claims, err := s.product.VerifyAccountToken(ctx, token); err == nil {
			return "platform-user:" + claims.Subject, true
		}
	}
	if s.authToken != "" && subtle.ConstantTimeCompare([]byte(token), []byte(s.authToken)) == 1 {
		return "platform-static", true
	}
	if s.kubeClient != nil {
		result, err := s.kubeClient.AuthenticationV1().TokenReviews().Create(ctx,
			&authv1.TokenReview{Spec: authv1.TokenReviewSpec{Token: token}}, metav1.CreateOptions{})
		if err == nil && result.Status.Authenticated {
			principal := result.Status.User.UID
			if principal == "" {
				principal = result.Status.User.Username
			}
			return "workload:" + principal, principal != ""
		}
	}
	return "", false
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
		token := c.GetHeader("X-Agent-Task-Token")
		bearerCandidate := false
		if token == "" && c.Request.URL.Path == "/mcp/collaboration" {
			token = requestBearerToken(c)
			bearerCandidate = token != ""
		}
		if token != "" {
			verify := s.verifyActiveTaskToken
			if c.Request.Method == http.MethodPost &&
				(strings.HasSuffix(c.Request.URL.Path, "/run/node/complete") ||
					strings.HasSuffix(c.Request.URL.Path, "/run/node/fail")) {
				verify = s.verifyCoordinatorTaskToken
			}
			task, err := verify(c.Request.Context(), token, uuid.Nil)
			completedCoordinator := false
			if err != nil && c.Request.URL.Path == "/mcp/collaboration" {
				if coordinatorTask, coordinatorErr := s.verifyCoordinatorTaskToken(
					c.Request.Context(), token, uuid.Nil); coordinatorErr == nil {
					task, err, completedCoordinator = coordinatorTask, nil, true
				}
			}
			if err != nil {
				if !bearerCandidate {
					c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: err.Error()})
					return
				}
			} else {
				c.Set(ctxInternalAuth, true)
				c.Set(ctxTaskAuth, task)
				if completedCoordinator {
					c.Set(ctxCompletedCoordinatorAuth, true)
				}
				c.Next()
				return
			}
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
	return s.verifyTaskToken(ctx, token, expectedTaskID, false)
}

// verifyCoordinatorTaskToken permits a Team leader to perform the final explicit coordinator
// transition with the same fenced token immediately after its physical attempt completes. All
// other task-scoped APIs remain restricted to a live attempt.
func (s *Server) verifyCoordinatorTaskToken(ctx context.Context, token string, expectedTaskID uuid.UUID) (*controlmodel.AgentTask, error) {
	return s.verifyTaskToken(ctx, token, expectedTaskID, true)
}

func (s *Server) verifyTaskToken(ctx context.Context, token string, expectedTaskID uuid.UUID, allowCompletedCoordinator bool) (*controlmodel.AgentTask, error) {
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
	if err != nil || attempt.DispatchGeneration != claims.Generation {
		return nil, fmt.Errorf("task token generation is no longer active")
	}
	if controlmodel.IsExecutionAttemptTerminal(attempt.State) &&
		(!allowCompletedCoordinator || !task.LeaderTask ||
			(task.Status != controlmodel.AgentTaskCompleted && task.Status != controlmodel.AgentTaskFailed)) {
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
		case "/api/v1/agent-tasks/:taskId/workspace-application", "/api/v1/agent-tasks/:taskId", "/api/v1/agent-tasks/:taskId/context",
			"/api/v1/agent-tasks/:taskId/claim", "/api/v1/agent-tasks/:taskId/ack",
			"/api/v1/agent-tasks/:taskId/inputs/delivery-failed", "/api/v1/agent-tasks/:taskId/start",
			"/api/v1/agent-tasks/:taskId/progress", "/api/v1/agent-tasks/:taskId/respond",
			"/api/v1/agent-tasks/:taskId/runtime-approvals",
			"/api/v1/agent-tasks/:taskId/runtime-approvals/:approvalId/decision",
			"/api/v1/agent-tasks/:taskId/runtime-approvals/:approvalId/ack",
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
	if s.product != nil {
		go s.product.RunChannelWork(ctx)
	}
	if s.workSources != nil {
		go func() {
			ticker := time.NewTicker(5 * time.Second)
			defer ticker.Stop()
			for {
				_, _ = s.workSources.FlushPendingComments(ctx, 100)
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
				}
			}
		}()
	}
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
