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
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	authv1 "k8s.io/api/authentication/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/sessionops"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/team"
	"github.com/spring-ai-alibaba/aistio/internal/version"
)

// SessionCommandSender dispatches a session command (compress/terminate)
// over a live ASDP stream. Implemented by asdp.Distributor.
type SessionCommandSender interface {
	SendSessionCommand(namespace, instanceID, sessionID, command string) error
}

// InventoryProvider exposes the latest per-instance inventory reports held
// by the ASDP connection registry. Implemented by asdp.Server.
type InventoryProvider interface {
	GetInventoriesForAgent(namespace, agentName string) []*asdp.InstanceInventory
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
	// HostedStore enables the /api/v1/dp/* hosted DistributedStore API.
	HostedStore bool
	// TranscriptMessages optionally reads Level-3 message history from a
	// control-plane transcript store (NAS / object storage). When it returns
	// ok=true, getSessionMessages skips the live DP fallback and does not
	// require the message-query capability.
	TranscriptMessages TranscriptMessagesFunc
	// Pre-built shared Team runtime (optional). When nil and Store is set,
	// NewServer constructs Lifecycle/MessageRouter/TaskStore itself.
	TeamLifecycle *team.Lifecycle
	TeamTaskStore *team.TaskStore
	TeamRouter    *team.MessageRouter
}

// Server is the REST API server for the control plane.
type Server struct {
	client             client.Client
	store              store.Store
	prober             prober.DataPlaneProber
	router             *gin.Engine
	httpServer         *http.Server
	experimental       bool
	authToken          string
	tlsCertFile        string
	tlsKeyFile         string
	kubeClient         kubernetes.Interface
	asdpCommands       SessionCommandSender
	asdpInventory      InventoryProvider
	product            *product.Server
	staticDir          string
	registry           *dataplane.Registry
	internalToken      string
	hostedStore        bool
	transcriptMessages TranscriptMessagesFunc
	sessionOps         *sessionops.Router

	// Team coordination state (store-backed; available whenever Store is set).
	taskStore      *team.TaskStore
	messageRouter  *team.MessageRouter
	teamLifecycle  *team.Lifecycle
}

// NewServer creates a new API server.
func NewServer(opts ServerOptions) *Server {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(gin.Logger())

	s := &Server{
		client:             opts.Client,
		store:              opts.Store,
		prober:             opts.Prober,
		router:             router,
		experimental:       opts.Experimental,
		authToken:          opts.AuthToken,
		tlsCertFile:        opts.TLSCertFile,
		tlsKeyFile:         opts.TLSKeyFile,
		kubeClient:         opts.KubeClient,
		asdpCommands:       opts.ASDPCommands,
		asdpInventory:      opts.ASDPInventory,
		product:            opts.Product,
		staticDir:          opts.StaticDir,
		registry:           opts.Registry,
		internalToken:      opts.InternalToken,
		hostedStore:        opts.HostedStore,
		transcriptMessages: opts.TranscriptMessages,
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
	}

	if opts.KubeClient == nil {
		ctrl.Log.WithName("httpapi").Info("authorization disabled: no kube client configured (static token mode does not support authorization)")
	}

	if opts.Store != nil {
		if opts.TeamLifecycle != nil && opts.TeamTaskStore != nil && opts.TeamRouter != nil {
			s.teamLifecycle = opts.TeamLifecycle
			s.taskStore = opts.TeamTaskStore
			s.messageRouter = opts.TeamRouter
		} else {
			s.taskStore = team.NewTaskStore(opts.Store.TeamTasks())
			s.messageRouter = team.NewMessageRouter(opts.Store.TeamMessages(), opts.Store.Sessions())
			spawner := team.NewSessionSpawner(opts.Store)
			s.teamLifecycle = team.NewLifecycle(opts.Store, s.taskStore, s.messageRouter, spawner)
			var commander team.SessionCommander
			if c, ok := opts.ASDPCommands.(team.SessionCommander); ok {
				commander = c
			}
			act := team.NewActivator(opts.Store, opts.Registry, commander)
			if opts.Product != nil {
				act.SetManagedSessionAPI(opts.Product)
				st := opts.Store
				opts.Product.SetTeamContextLookup(func(ctx context.Context, sessionID string) json.RawMessage {
					list, err := st.Sessions().List(ctx, store.SessionFilter{SessionID: sessionID, Limit: 1})
					if err != nil || len(list) == 0 {
						return nil
					}
					return list[0].TeamContext
				})
				opts.Product.SetTeamMemberActivityHook(func(ctx context.Context, sessionID, status string) {
					_ = team.SyncMemberPhaseFromSessionStatus(ctx, st, sessionID, status)
				})
			}
			s.teamLifecycle.SetActivator(act)
		}
	}

	s.registerRoutes()
	return s
}

// SessionOps returns the session command router, or nil when store/registry
// are unavailable.
func (s *Server) SessionOps() *sessionops.Router {
	return s.sessionOps
}

// TeamLifecycle returns the shared store-backed team lifecycle, or nil.
func (s *Server) TeamLifecycle() *team.Lifecycle {
	return s.teamLifecycle
}

// TeamMessageRouter returns the shared team mailbox router, or nil.
func (s *Server) TeamMessageRouter() *team.MessageRouter {
	return s.messageRouter
}

// TeamTaskStore returns the shared team task store, or nil.
func (s *Server) TeamTaskStore() *team.TaskStore {
	return s.taskStore
}

func (s *Server) registerRoutes() {
	// System
	s.router.GET("/healthz", s.healthz)
	s.router.GET("/readyz", s.readyz)
	s.router.GET("/actuator/health", s.healthz)
	s.router.GET("/api/v1/version", s.version)

	// Managed Agents control plane. Mounted on an unprefixed group so its
	// JWT/internal-token chain applies only to the product routes.
	if s.product != nil {
		pg := s.router.Group("")
		pg.Use(s.product.Middlewares()...)
		s.product.Register(pg)
	}

	v1 := s.router.Group("/api/v1")
	v1.Use(s.authMiddleware())
	v1.Use(s.authzMiddleware())
	{
		// Fleet overview + token metrics (store-backed).
		if s.store != nil {
			v1.GET("/overview", s.fleetOverview)
			v1.GET("/overview/timeseries", s.overviewTimeseries)
			v1.GET("/metrics/tokens", s.queryTokenMetrics)
			v1.GET("/metrics/agents", s.queryAgentMetrics)
		}

		// Data-plane self-registration (internal token). Listed for console
		// under JWT auth; mutations use a separate group below.
		v1.GET("/dataplanes", s.listDataPlanes)

		// Agent lifecycle. CRD-backed when Kubernetes is available; otherwise
		// serve summaries from the self-registration registry.
		if s.client != nil {
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
		} else {
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

	// Teams: store-backed (standalone-safe). Accept console JWT OR internal token
	// so ControlPlaneTeamClient (X-Builder-Internal-Token) can call teamsMode tools.
	if s.store != nil {
		teams := s.router.Group("/api/v1/teams")
		teams.Use(s.teamsAuthMiddleware())
		teams.Use(s.authzMiddleware())
		{
			teams.POST("", s.createTeam)
			teams.GET("", s.listTeams)
			teams.GET("/:team", s.getTeam)
			teams.POST("/:team/complete", s.completeTeam)
			teams.DELETE("/:team", s.deleteTeam)
			teams.POST("/:team/members", s.addTeamMember)
			teams.DELETE("/:team/members/:memberName", s.removeTeamMember)
			teams.GET("/:team/members", s.listTeamMembers)
			teams.POST("/:team/members/:memberName/plan", s.submitTeamMemberPlan)
			teams.POST("/:team/members/:memberName/plan/approve", s.approveTeamMemberPlan)
			teams.POST("/:team/members/:memberName/plan/reject", s.rejectTeamMemberPlan)
			teams.POST("/:team/tasks", s.createTeamTask)
			teams.GET("/:team/tasks", s.listTeamTasks)
			teams.POST("/:team/tasks/:taskId/assign", s.assignTeamTask)
			teams.POST("/:team/tasks/:taskId/claim", s.claimTeamTask)
			teams.POST("/:team/tasks/:taskId/unclaim", s.unclaimTeamTask)
			teams.POST("/:team/tasks/:taskId/complete", s.completeTeamTask)
			teams.POST("/:team/tasks/:taskId/fail", s.failTeamTask)
			teams.POST("/:team/messages", s.sendTeamMessage)
			teams.GET("/:team/messages", s.listTeamMessages)
			teams.GET("/:team/events", s.listTeamEvents)
		}
	}

	// Data-plane self-registration: authenticated by the shared internal token
	// so workers can register without a console JWT.
	dp := s.router.Group("/api/v1/dataplanes")
	dp.Use(s.internalTokenMiddleware())
	{
		dp.POST("/register", s.registerDataPlane)
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
		auth := c.GetHeader("Authorization")
		token := strings.TrimPrefix(auth, "Bearer ")

		if s.product != nil {
			if claims, err := s.product.VerifyToken(token); err == nil {
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
			if auth == "" || token != s.authToken {
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

// teamsAuthMiddleware accepts either the shared internal token (data plane)
// or the normal console/JWT/kube auth chain.
func (s *Server) teamsAuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
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

// kubeAuth validates a bearer token via the Kubernetes TokenReview API.
func (s *Server) kubeAuth(c *gin.Context) {
	auth := c.GetHeader("Authorization")
	token := strings.TrimPrefix(auth, "Bearer ")
	if token == "" || token == auth {
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
