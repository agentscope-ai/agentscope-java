package main

import (
	"context"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/go-logr/logr"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/rest"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/client-go/kubernetes"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/healthz"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"
	metricsserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
	"sigs.k8s.io/controller-runtime/pkg/webhook/admission"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	"github.com/spring-ai-alibaba/aistio/internal/controller"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/discovery"
	"github.com/spring-ai-alibaba/aistio/internal/httpapi"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/sessionops"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"github.com/spring-ai-alibaba/aistio/internal/team"
	"github.com/spring-ai-alibaba/aistio/internal/tracing"
)

var (
	version   = "dev"
	gitCommit = "unknown"
	buildDate = "unknown"
)

var (
	scheme = runtime.NewScheme()
)

// distributorAdapter adapts asdp.Distributor (proto ConfigType) to the
// controller.ConfigDistributor interface (int32 configType).
type distributorAdapter struct {
	dist *asdp.Distributor
}

func (a *distributorAdapter) PushConfig(namespace, agentName string, configType int32, resources interface{}) error {
	return a.dist.PushConfig(namespace, agentName, asdp.ConfigType(configType), resources)
}

func (a *distributorAdapter) ForgetAgent(namespace, agentName string) {
	a.dist.ForgetAgent(namespace, agentName)
}

// sessionSinkAdapter adapts the asdp.EventSink interface (proto types) to the
// controller.SessionEventSink (neutral types), so upstream gRPC reports reach
// the runtime Store without coupling the controller package to asdp.
type sessionSinkAdapter struct {
	sink     *controller.SessionEventSink
	teamSink *controller.TeamEventSink
}

func (a *sessionSinkAdapter) HandleSessionReport(namespace, agentName, instanceID string, report *asdp.SessionReport) {
	if report == nil {
		return
	}
	observed := make([]controller.ObservedSession, 0, len(report.Sessions))
	for _, s := range report.Sessions {
		if s == nil {
			continue
		}
		observed = append(observed, controller.ObservedSession{
			ID:                    s.GetSessionId(),
			Phase:                 s.GetPhase(),
			MessageCount:          s.GetMessageCount(),
			PromptTokens:          s.GetPromptTokens(),
			CompletionTokens:      s.GetCompletionTokens(),
			ContextPressure:       s.GetContextPressure(),
			TeamID:                s.GetTeamId(),
			TeamRole:              s.GetTeamRole(),
			Framework:             s.GetFramework(),
			FrameworkVersion:      s.GetFrameworkVersion(),
			ContextHash:           s.GetContextHash(),
			IsCompacted:           s.GetIsCompacted(),
			EffectiveMessageCount: s.GetEffectiveMessageCount(),
		})
	}
	a.sink.ApplySessionReport(context.Background(), namespace, agentName, instanceID, observed)
}

// HandleEventReport maps an ASDP Level-2 event batch to the store sink.
func (a *sessionSinkAdapter) HandleEventReport(namespace, agentName, instanceID string, report *asdp.EventReport) {
	if report == nil || len(report.Events) == 0 {
		return
	}
	events := make([]controller.ObservedEvent, 0, len(report.Events))
	for _, e := range report.Events {
		if e == nil {
			continue
		}
		events = append(events, controller.ObservedEvent{
			SessionID:     e.GetSessionId(),
			Seq:           e.GetSeq(),
			EventType:     e.GetEventType(),
			OccurredAt:    unixMsToTime(e.GetOccurredAt()),
			Role:          e.GetRole(),
			Content:       e.GetContent(),
			ToolName:      e.GetToolName(),
			ToolInput:     e.GetToolInput(),
			ToolOutput:    e.GetToolOutput(),
			TokensIn:      e.GetTokensIn(),
			TokensOut:     e.GetTokensOut(),
			DurationMs:    e.GetDurationMs(),
			FrameworkMeta: e.GetFrameworkMeta(),
		})
	}
	a.sink.ApplyEventReport(context.Background(), namespace, agentName, instanceID, events)
}

// HandleContextReport maps an ASDP Level-4 context report to the store sink.
func (a *sessionSinkAdapter) HandleContextReport(namespace, agentName, instanceID string, report *asdp.ContextReport) {
	if report == nil {
		return
	}
	a.sink.ApplyContextReport(context.Background(), namespace, agentName, instanceID, controller.ObservedContext{
		SessionID:            report.GetSessionId(),
		ContextHash:          report.GetContextHash(),
		CapturedAt:           unixMsToTime(report.GetCapturedAt()),
		SystemPrompt:         report.GetSystemPrompt(),
		Messages:             report.GetMessages(),
		Tools:                report.GetTools(),
		IsCompacted:          report.GetIsCompacted(),
		CompactionSummary:    report.GetCompactionSummary(),
		OriginalMessageCount: report.GetOriginalMessageCount(),
		CompactedAt:          unixMsToTimePtr(report.GetCompactedAt()),
		TotalTokens:          report.GetTotalTokens(),
		MaxTokens:            report.GetMaxTokens(),
		Framework:            report.GetFramework(),
		FrameworkState:       report.GetFrameworkState(),
	})
}

// HandleInventoryReport maps an ASDP inventory report to the store sink.
func (a *sessionSinkAdapter) HandleInventoryReport(namespace, agentName, instanceID string, report *asdp.InventoryReport) {
	if report == nil {
		return
	}
	inv := controller.ObservedInventory{}
	for _, sa := range report.GetSubagents() {
		inv.Subagents = append(inv.Subagents, controller.ObservedSubagent{
			Name:          sa.GetName(),
			Description:   sa.GetDescription(),
			Tools:         sa.GetTools(),
			WorkspaceMode: sa.GetWorkspaceMode(),
			URL:           sa.GetUrl(),
			InvokeCount:   sa.GetInvokeCount(),
			LastInvokedAt: unixMsToTimePtr(sa.GetLastInvokedAt()),
		})
	}
	for _, ws := range report.GetWorkspaces() {
		inv.Workspaces = append(inv.Workspaces, controller.ObservedWorkspace{
			Path:      ws.GetPath(),
			Mode:      ws.GetMode(),
			SizeBytes: ws.GetSizeBytes(),
			OwnerRef:  ws.GetOwnerRef(),
		})
	}
	if h := report.GetHealth(); h != nil {
		inv.Healthy = h.GetHealthy()
		inv.HealthReason = h.GetReason()
		inv.ActiveSessions = h.GetActiveSessions()
	}
	a.sink.ApplyInventoryReport(context.Background(), namespace, agentName, instanceID, inv)
}

// unixMsToTime converts unix milliseconds to UTC time; 0 yields the zero time.
func unixMsToTime(ms int64) time.Time {
	if ms <= 0 {
		return time.Time{}
	}
	return time.UnixMilli(ms).UTC()
}

func unixMsToTimePtr(ms int64) *time.Time {
	if ms <= 0 {
		return nil
	}
	t := time.UnixMilli(ms).UTC()
	return &t
}

func (a *sessionSinkAdapter) HandleTeamEventReport(namespace, agentName string, report *asdp.TeamEventReport) {
	if a.teamSink == nil || report == nil {
		return
	}
	a.teamSink.HandleEvent(context.Background(), namespace, &controller.TeamEventReport{
		TeamID:     report.GetTeamId(),
		EventType:  report.GetEventType(),
		MemberName: report.GetMemberName(),
		TaskID:     report.GetTaskId(),
		Detail:     controller.ParseDetail(report.GetDetail()),
	})
}

func init() {
	utilruntime.Must(clientgoscheme.AddToScheme(scheme))
	utilruntime.Must(v1alpha1.AddToScheme(scheme))
}

func main() {
	var (
		metricsAddr          string
		probeAddr            string
		httpAddr             string
		grpcAddr             string
		enableLeaderElection bool
		enableASDP           bool
		enableExperimental   bool
		enableWebhook        bool
		showVersion          bool
		apiAuthToken         string
		apiTLSCert           string
		apiTLSKey            string
		enableKubeAuth       bool
		logFormat            string
		otelEndpoint         string
		traceSampling        float64
		grpcTLSCert          string
		grpcTLSKey           string
		grpcTLSCA            string

		healthCheck      bool
		enableKubernetes bool
		enableProduct    bool
		productDSN       string
		productJWTSecret string
		productToken     string
		workspaceRoot    string
		staticDir        string
		seedUsers        bool

		storageDriver          string
		storageDSN             string
		storageMaxOpenConns    int
		storageMaxIdleConns    int
		storageConnMaxLifetime time.Duration
		retentionSessionEvents time.Duration
		retentionSnapshots     time.Duration
		retentionContexts      time.Duration
		retentionMetrics       time.Duration
	)

	defaultRetention := store.DefaultRetention()
	productDefaults := product.DefaultConfig()

	flag.StringVar(&metricsAddr, "metrics-bind-address", ":8181", "The address the metrics endpoint binds to.")
	flag.StringVar(&probeAddr, "health-probe-bind-address", ":8182", "The address the probe endpoint binds to.")
	flag.StringVar(&httpAddr, "http-bind-address", envOr("AISTIO_HTTP_BIND", ":8080"),
		"The address the REST API server binds to. Serves the Kubernetes-native API, the Managed Agents API, and the console SPA.")
	flag.StringVar(&grpcAddr, "grpc-bind-address", ":15010", "The address the ASDP gRPC server binds to.")
	flag.BoolVar(&enableLeaderElection, "leader-elect", false, "Enable leader election.")
	flag.BoolVar(&enableASDP, "enable-asdp", true,
		"Enable the ASDP data plane protocol (gRPC coordination, config push). On by default.")
	flag.BoolVar(&enableExperimental, "enable-experimental", false,
		"Enable experimental features (distributed AgentTeam, sandbox provisioning). Off by default.")
	flag.BoolVar(&enableWebhook, "enable-webhook", false, "Enable the Agent validating admission webhook (requires serving certs).")
	flag.BoolVar(&showVersion, "version", false, "Print version information and exit.")
	flag.StringVar(&apiAuthToken, "api-auth-token", os.Getenv("AGENTSCOPE_API_TOKEN"),
		"Optional bearer token required for REST API access. Empty disables auth.")
	flag.StringVar(&apiTLSCert, "api-tls-cert", "", "TLS certificate file for REST API server.")
	flag.StringVar(&apiTLSKey, "api-tls-key", "", "TLS key file for REST API server.")
	flag.BoolVar(&enableKubeAuth, "enable-kube-auth", false, "Enable Kubernetes TokenReview authentication for REST API.")
	flag.StringVar(&logFormat, "log-format", "json", "Log output format: json (default) or console (human-readable).")
	flag.StringVar(&otelEndpoint, "otel-endpoint", "", "OpenTelemetry collector endpoint (empty disables tracing).")
	flag.Float64Var(&traceSampling, "trace-sampling", 1.0, "Trace sampling rate (0.0-1.0).")
	flag.StringVar(&grpcTLSCert, "grpc-tls-cert", "", "TLS certificate for gRPC server.")
	flag.StringVar(&grpcTLSKey, "grpc-tls-key", "", "TLS key for gRPC server.")
	flag.StringVar(&grpcTLSCA, "grpc-tls-ca", "", "CA certificate for client verification (enables mTLS).")

	flag.BoolVar(&healthCheck, "healthcheck", false,
		"Probe the local REST API and exit 0 when healthy. Intended for container health checks.")
	flag.BoolVar(&enableKubernetes, "enable-kubernetes", envBool("AISTIO_ENABLE_KUBERNETES", true),
		"Connect to Kubernetes for CRD-backed APIs, reconcilers, and ASDP. Disable to run the control plane standalone.")
	flag.BoolVar(&enableProduct, "enable-product", envBool("AISTIO_ENABLE_PRODUCT", true),
		"Serve the Managed Agents control plane (/api/*, console SPA) from this process. Requires --product-dsn.")
	flag.StringVar(&productDSN, "product-dsn", envOr("AISTIO_PRODUCT_DSN", ""),
		"PostgreSQL DSN for the Managed Agents control plane (schema cp). Empty leaves that API unmounted.")
	flag.StringVar(&productJWTSecret, "jwt-secret", envOr("BUILDER_JWT_SECRET", productDefaults.JWTSecret),
		"HMAC secret for console JWTs (minimum 32 characters).")
	flag.StringVar(&productToken, "internal-token", envOr("BUILDER_INTERNAL_TOKEN", productDefaults.InternalToken),
		"Shared token required on /api/internal/* by data plane and scheduler.")
	flag.StringVar(&workspaceRoot, "workspace-root", envOr("AISTIO_WORKSPACE_ROOT", productDefaults.WorkspaceRoot),
		"Filesystem root for agent workspaces.")
	flag.StringVar(&staticDir, "static-dir", envOr("AISTIO_STATIC_DIR", ""),
		"Directory holding the built console SPA. Empty disables static serving.")
	flag.BoolVar(&seedUsers, "seed-users", envBool("AISTIO_SEED_USERS", true),
		"Seed default console users when the users table is empty.")

	flag.StringVar(&storageDriver, "storage-driver", store.DriverMemory,
		"Runtime data storage driver: memory (dev/test, non-durable) or postgres (production).")
	flag.StringVar(&storageDSN, "storage-dsn", os.Getenv("AISTIO_STORAGE_DSN"),
		"PostgreSQL DSN for the storage driver (e.g. postgres://user:pass@host:5432/aistio?sslmode=require). Required when --storage-driver=postgres.")
	flag.IntVar(&storageMaxOpenConns, "storage-max-open-conns", 20, "Maximum open connections to the storage backend.")
	flag.IntVar(&storageMaxIdleConns, "storage-max-idle-conns", 5, "Maximum idle connections to the storage backend.")
	flag.DurationVar(&storageConnMaxLifetime, "storage-conn-max-lifetime", 30*time.Minute, "Maximum lifetime of a storage backend connection.")
	flag.DurationVar(&retentionSessionEvents, "retention-session-events", defaultRetention.SessionEvents, "Retention window for session events.")
	flag.DurationVar(&retentionSnapshots, "retention-snapshots", defaultRetention.Snapshots, "Retention window for session snapshots and metrics.")
	flag.DurationVar(&retentionContexts, "retention-context-snapshots", defaultRetention.ContextSnapshots, "Retention window for full context snapshots.")
	flag.DurationVar(&retentionMetrics, "retention-metrics", defaultRetention.Metrics, "Retention window for token/agent metrics.")
	flag.Parse()

	if showVersion {
		fmt.Printf("aistiod %s (commit: %s, built: %s)\n", version, gitCommit, buildDate)
		os.Exit(0)
	}

	if healthCheck {
		if err := probeSelf(httpAddr); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		os.Exit(0)
	}

	// Structured logging: default to production JSON; use console mode for
	// local development via --log-format=console or AGENTSCOPE_DEV_LOG=true.
	opts := zap.Options{
		Development: false,
	}
	if logFormat == "console" || os.Getenv("AGENTSCOPE_DEV_LOG") == "true" {
		opts.Development = true
	}
	ctrl.SetLogger(zap.New(zap.UseFlagOptions(&opts)))
	logger := ctrl.Log.WithName("setup")
	logger.Info("starting aistio", "version", version, "commit", gitCommit, "buildDate", buildDate)

	if otelEndpoint != "" {
		shutdownTracing, err := tracing.Init(context.Background(), otelEndpoint, traceSampling)
		if err != nil {
			logger.Error(err, "failed to initialize tracing")
		} else {
			defer shutdownTracing()
			logger.Info("OpenTelemetry tracing initialized", "endpoint", otelEndpoint)
		}
	}

	// Open the runtime data store (sessions, events, context snapshots,
	// metrics, team messages/tasks). Memory is the default for local/dev use
	// and unit tests; it is NOT durable across restarts.
	storeCfg := store.Config{
		Driver:          storageDriver,
		PostgresDSN:     storageDSN,
		MaxOpenConns:    storageMaxOpenConns,
		MaxIdleConns:    storageMaxIdleConns,
		ConnMaxLifetime: storageConnMaxLifetime,
		Retention: store.RetentionConfig{
			SessionEvents:    retentionSessionEvents,
			Snapshots:        retentionSnapshots,
			ContextSnapshots: retentionContexts,
			Metrics:          retentionMetrics,
		},
	}
	runtimeStore, err := store.Open(context.Background(), storeCfg)
	if err != nil {
		logger.Error(err, "unable to open runtime store", "driver", storageDriver)
		os.Exit(1)
	}
	defer runtimeStore.Close()
	if storageDriver == store.DriverMemory {
		logger.Info("WARNING: using the in-memory storage driver -- session, event, and team data will NOT survive a restart. Use --storage-driver=postgres for production deployments.")
	}

	// Managed Agents control plane. Owns the `cp` schema and the console
	// facing /api/* surface; mounted on the shared REST server below.
	var productSrv *product.Server
	switch {
	case !enableProduct:
	case productDSN == "":
		logger.Info("Managed Agents control plane not mounted: no --product-dsn configured")
	default:
		productSrv, err = product.Open(context.Background(), product.Config{
			DSN:            productDSN,
			JWTSecret:      productJWTSecret,
			InternalToken:  productToken,
			WorkspaceRoot:  workspaceRoot,
			SeedUsers:      seedUsers,
			DataURL:        os.Getenv("BUILDER_DATA_URL"),
			VaultMasterKey: os.Getenv("BUILDER_VAULT_MASTER_KEY"),
		})
		if err != nil {
			logger.Error(err, "unable to open the Managed Agents control plane")
			os.Exit(1)
		}
		defer productSrv.Close()
		logger.Info("Managed Agents control plane enabled", "workspaceRoot", workspaceRoot, "staticDir", staticDir)
	}

	// Kubernetes is optional: without a reachable cluster aistiod still serves
	// the Managed Agents API, the console, and the store-backed session views,
	// but no CRD-backed resources or reconcilers.
	var mgr ctrl.Manager
	var restCfg *rest.Config
	var kubeErr error
	if enableKubernetes {
		restCfg, kubeErr = ctrl.GetConfig()
	} else {
		kubeErr = fmt.Errorf("disabled via --enable-kubernetes=false")
	}
	if kubeErr != nil {
		logger.Info("running without controllers or CRD-backed APIs", "reason", kubeErr.Error())
	} else {
		mgr, err = ctrl.NewManager(restCfg, ctrl.Options{
			Scheme:                 scheme,
			Metrics:                metricsserver.Options{BindAddress: metricsAddr},
			HealthProbeBindAddress: probeAddr,
			LeaderElection:         enableLeaderElection,
			LeaderElectionID:       "aistio.agentscope.io",
		})
		if err != nil {
			logger.Error(err, "unable to create manager")
			os.Exit(1)
		}
	}

	// Shared components
	httpProber := prober.NewHTTPProber()
	dpRegistry := dataplane.NewRegistry()

	var asdpServer *asdp.Server
	if mgr != nil {
		asdpServer = setupKubernetes(kubeRuntime{
			mgr:                mgr,
			logger:             logger,
			store:              runtimeStore,
			storeCfg:           storeCfg,
			prober:             httpProber,
			enableASDP:         enableASDP,
			enableExperimental: enableExperimental,
			enableWebhook:      enableWebhook,
			grpcAddr:           grpcAddr,
			grpcTLSCert:        grpcTLSCert,
			grpcTLSKey:         grpcTLSKey,
			grpcTLSCA:          grpcTLSCA,
		})
	} else if enableASDP {
		logger.Info("ASDP disabled: the data plane protocol requires a Kubernetes connection")
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Handle shutdown signals
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		logger.Info("received shutdown signal")
		cancel()
	}()

	// Self-registration poller: always runs so standalone (no K8s) fleets
	// still get session snapshots into the runtime store.
	go (&dataplane.Poller{
		Registry: dpRegistry,
		Store:    runtimeStore,
		Prober:   httpProber,
		Interval: 15 * time.Second,
	}).Run(ctx)
	logger.Info("data plane self-registration poller started")

	// Start the ASDP gRPC server.
	// Multi-replica note: the gRPC server runs on ALL replicas, not just the
	// leader. Each replica accepts data plane connections and pushes config to
	// its own connections via the local informer cache. Controller reconcile
	// loops remain leader-gated via the manager's leader election.
	if asdpServer != nil {
		go func() {
			logger.Info("starting ASDP gRPC server", "addr", grpcAddr)
			if err := asdpServer.Start(); err != nil {
				logger.Error(err, "ASDP gRPC server error")
			}
		}()
		go func() {
			<-ctx.Done()
			asdpServer.Stop()
		}()
	}

	// Build REST API server options. One listener serves the Kubernetes-native
	// API, the Managed Agents API, and the console SPA.
	apiOpts := httpapi.ServerOptions{
		Store:         runtimeStore,
		Prober:        httpProber,
		Addr:          httpAddr,
		Experimental:  enableExperimental,
		AuthToken:     apiAuthToken,
		TLSCertFile:   apiTLSCert,
		TLSKeyFile:    apiTLSKey,
		Product:       productSrv,
		StaticDir:     staticDir,
		Registry:      dpRegistry,
		InternalToken: productToken,
	}
	if mgr != nil {
		apiOpts.Client = mgr.GetClient()
	}
	if asdpServer != nil {
		// Session commands prefer live ASDP streams; instance inventory is
		// served from the ASDP connection registry.
		apiOpts.ASDPCommands = asdpServer.Distributor()
		apiOpts.ASDPInventory = asdpServer
	}
	if enableKubeAuth && restCfg != nil {
		kubeClient, err := kubernetes.NewForConfig(restCfg)
		if err != nil {
			logger.Error(err, "unable to create Kubernetes clientset for API auth")
			os.Exit(1)
		}
		apiOpts.KubeClient = kubeClient
	}

	apiServer := httpapi.NewServer(apiOpts)

	if ops := apiServer.SessionOps(); ops != nil && runtimeStore != nil {
		go (&sessionops.QueueWorker{
			Router:   ops,
			Store:    runtimeStore,
			Interval: 3 * time.Second,
			Batch:    20,
		}).Run(ctx)
		logger.Info("session command queue worker started")
	}

	// Without a manager the REST server is the only long-running component,
	// so it runs in the foreground.
	if mgr == nil {
		logger.Info("starting REST API server", "addr", httpAddr, "kubernetes", false,
			"product", productSrv != nil, "tls", apiTLSCert != "")
		if err := apiServer.Start(ctx); err != nil {
			logger.Error(err, "REST API server error")
			os.Exit(1)
		}
		return
	}

	go func() {
		logger.Info("starting REST API server", "addr", httpAddr, "experimental", enableExperimental,
			"product", productSrv != nil, "tls", apiTLSCert != "", "kubeAuth", enableKubeAuth)
		if err := apiServer.Start(ctx); err != nil {
			logger.Error(err, "REST API server error")
		}
	}()

	// Start controller manager (blocking)
	logger.Info("starting controller manager")
	if err := mgr.Start(ctx); err != nil {
		logger.Error(err, "controller manager error")
		os.Exit(1)
	}
}

// kubeRuntime carries everything the Kubernetes-dependent wiring needs.
type kubeRuntime struct {
	mgr                ctrl.Manager
	logger             logr.Logger
	store              store.Store
	storeCfg           store.Config
	prober             prober.DataPlaneProber
	enableASDP         bool
	enableExperimental bool
	enableWebhook      bool
	grpcAddr           string
	grpcTLSCert        string
	grpcTLSKey         string
	grpcTLSCA          string
}

// setupKubernetes registers the reconcilers, config delivery, admission
// webhooks, and health checks that require a cluster connection. It returns
// the ASDP server when the data plane protocol is enabled.
func setupKubernetes(k kubeRuntime) *asdp.Server {
	mgr, logger, runtimeStore, httpProber := k.mgr, k.logger, k.store, k.prober

	// Build ASDP server for data plane coordination.
	// Created early so core controllers can receive the distributor.
	var dist controller.ConfigDistributor
	var asdpServer *asdp.Server
	var sinkAdapter *sessionSinkAdapter
	if k.enableASDP {
		srv, err := asdp.NewServer(asdp.ServerConfig{
			Addr:      k.grpcAddr,
			TLSCert:   k.grpcTLSCert,
			TLSKey:    k.grpcTLSKey,
			TLSCACert: k.grpcTLSCA,
		})
		if err != nil {
			logger.Error(err, "unable to create ASDP gRPC server")
			os.Exit(1)
		}
		asdpServer = srv
		dist = &distributorAdapter{dist: asdpServer.Distributor()}
		// Wire upstream session reports through to the runtime Store.
		// teamSink is set later once taskStore is available (if experimental is enabled).
		sinkAdapter = &sessionSinkAdapter{
			sink: &controller.SessionEventSink{Client: mgr.GetClient(), Store: runtimeStore},
		}
		asdpServer.SetEventSink(sinkAdapter)
		logger.Info("ASDP data plane protocol enabled")
	}

	enableExperimental := k.enableExperimental
	enableWebhook := k.enableWebhook
	storeCfg := k.storeCfg

	// ===== v0.1 core controllers (always registered) =====
	if err := (&controller.AgentReconciler{
		Client:   mgr.GetClient(),
		Scheme:   mgr.GetScheme(),
		Prober:   httpProber,
		Store:    runtimeStore,
		Recorder: mgr.GetEventRecorderFor("agent-controller"),
	}).SetupWithManager(mgr); err != nil {
		logger.Error(err, "unable to create controller", "controller", "Agent")
		os.Exit(1)
	}

	if err := (&controller.DiscoveryReconciler{
		Client:   mgr.GetClient(),
		Scheme:   mgr.GetScheme(),
		Prober:   httpProber,
		Recorder: mgr.GetEventRecorderFor("discovery-controller"),
	}).SetupWithManager(mgr); err != nil {
		logger.Error(err, "unable to create controller", "controller", "Discovery")
		os.Exit(1)
	}

	if err := (&controller.BYOWorkloadReconciler{
		Client:   mgr.GetClient(),
		Scheme:   mgr.GetScheme(),
		Prober:   httpProber,
		Store:    runtimeStore,
		Recorder: mgr.GetEventRecorderFor("byoworkload-controller"),
	}).SetupWithManager(mgr); err != nil {
		logger.Error(err, "unable to create controller", "controller", "BYOWorkload")
		os.Exit(1)
	}

	if err := (&controller.ModelConfigReconciler{
		Client:   mgr.GetClient(),
		Scheme:   mgr.GetScheme(),
		Recorder: mgr.GetEventRecorderFor("modelconfig-controller"),
	}).SetupWithManager(mgr); err != nil {
		logger.Error(err, "unable to create controller", "controller", "ModelConfig")
		os.Exit(1)
	}

	if err := (&controller.MCPServerReconciler{
		Client:   mgr.GetClient(),
		Scheme:   mgr.GetScheme(),
		Recorder: mgr.GetEventRecorderFor("mcpserver-controller"),
	}).SetupWithManager(mgr); err != nil {
		logger.Error(err, "unable to create controller", "controller", "MCPServer")
		os.Exit(1)
	}

	if err := (&controller.SessionPollerReconciler{
		Client:   mgr.GetClient(),
		Scheme:   mgr.GetScheme(),
		Prober:   httpProber,
		Store:    runtimeStore,
		Recorder: mgr.GetEventRecorderFor("session-poller"),
	}).SetupWithManager(mgr); err != nil {
		logger.Error(err, "unable to create controller", "controller", "SessionPoller")
		os.Exit(1)
	}

	// RetentionWorker purges historical session/event/metric rows on a
	// schedule. Runs only on the leader. Registered unconditionally since the
	// runtime store is always configured (memory or postgres).
	if err := mgr.Add(&controller.RetentionWorker{
		Store:     runtimeStore,
		Retention: storeCfg.Retention,
	}); err != nil {
		logger.Error(err, "unable to add retention worker")
		os.Exit(1)
	}

	// ===== Config delivery (GA) =====
	// ConfigPushWatcher drives ASDP config push from every replica's informer
	// cache (NOT leader-gated), so config reaches data plane connections
	// regardless of which replica owns them. This is a core GA capability and is
	// registered whenever the ASDP server is enabled, independent of experimental.
	if dist != nil {
		if err := mgr.Add(&controller.ConfigPushWatcher{
			Client: mgr.GetClient(),
			Cache:  mgr.GetCache(),
			Dist:   dist,
		}); err != nil {
			logger.Error(err, "unable to add config push watcher")
			os.Exit(1)
		}
		logger.Info("ASDP config delivery enabled (agent/model/tool/skill hot-reload)")
	}

	// ===== Experimental controllers (gated) =====
	if enableExperimental {
		logger.Info("experimental features enabled (AgentTeam, SandboxBroker)")

		if err := (&controller.SandboxBrokerReconciler{
			Client:   mgr.GetClient(),
			Scheme:   mgr.GetScheme(),
			Recorder: mgr.GetEventRecorderFor("sandboxbroker-controller"),
		}).SetupWithManager(mgr); err != nil {
			logger.Error(err, "unable to create controller", "controller", "SandboxBroker")
			os.Exit(1)
		}

		taskStore := team.NewTaskStore(runtimeStore.TeamTasks())
		msgRouter := team.NewMessageRouter(runtimeStore.TeamMessages(), runtimeStore.Sessions())
		spawner := team.NewSessionSpawner(runtimeStore)
		lifecycle := team.NewLifecycle(mgr.GetClient(), runtimeStore, taskStore, msgRouter, spawner)

		// Wire team event processing now that taskStore is available.
		if sinkAdapter != nil {
			sinkAdapter.teamSink = controller.NewTeamEventSink(
				mgr.GetClient(), taskStore, mgr.GetEventRecorderFor("agentscope-controller"))
		}

		if err := (&controller.AgentTeamReconciler{
			Client:    mgr.GetClient(),
			Scheme:    mgr.GetScheme(),
			Recorder:  mgr.GetEventRecorderFor("agentscope-controller"),
			Lifecycle: lifecycle,
			Store:     runtimeStore,
		}).SetupWithManager(mgr); err != nil {
			logger.Error(err, "unable to create controller", "controller", "AgentTeam")
			os.Exit(1)
		}

		// The dispatcher delivers store-backed TeamMessages over the live gRPC
		// channel. It registers itself as non-leader (runs on every replica) so
		// it can reach connections held by any replica. The ASDP Distributor
		// satisfies the TeamEventDeliverer interface directly.
		var deliverer controller.TeamEventDeliverer
		if asdpServer != nil {
			deliverer = asdpServer.Distributor()
		}
		dispatcher := &controller.TeamMessageDispatcher{
			Store:     runtimeStore,
			Deliverer: deliverer,
		}
		if err := mgr.Add(dispatcher); err != nil {
			logger.Error(err, "unable to add team message dispatcher")
			os.Exit(1)
		}
	}

	// ===== Admission webhooks (gated; requires serving certs) =====
	if enableWebhook {
		decoder := admission.NewDecoder(mgr.GetScheme())
		mgr.GetWebhookServer().Register("/validate-agentscope-io-v1alpha1-agent",
			&admission.Webhook{Handler: discovery.NewAgentValidator(decoder)})
		mgr.GetWebhookServer().Register("/mutate-agentscope-io-v1alpha1-agent",
			&admission.Webhook{Handler: discovery.NewAgentDefaulter(decoder)})
		mgr.GetWebhookServer().Register("/validate-agentscope-io-v1alpha1-agentteam",
			&admission.Webhook{Handler: discovery.NewAgentTeamValidator(decoder)})
		mgr.GetWebhookServer().Register("/validate-agentscope-io-v1alpha1-modelconfig",
			&admission.Webhook{Handler: discovery.NewModelConfigValidator(decoder)})
		mgr.GetWebhookServer().Register("/validate-agentscope-io-v1alpha1-mcpserver",
			&admission.Webhook{Handler: discovery.NewMCPServerValidator(decoder)})
		logger.Info("admission webhooks registered")
	}

	// Health checks
	if err := mgr.AddHealthzCheck("healthz", healthz.Ping); err != nil {
		logger.Error(err, "unable to set up health check")
		os.Exit(1)
	}
	if err := mgr.AddReadyzCheck("readyz", healthz.Ping); err != nil {
		logger.Error(err, "unable to set up ready check")
		os.Exit(1)
	}
	if err := mgr.AddReadyzCheck("storage", func(req *http.Request) error {
		return runtimeStore.Ping(req.Context())
	}); err != nil {
		logger.Error(err, "unable to set up storage ready check")
		os.Exit(1)
	}

	return asdpServer
}

// probeSelf calls /healthz on the local REST listener so container runtimes
// can health check a distroless image without a shell.
func probeSelf(addr string) error {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("healthcheck: bad bind address %q: %w", addr, err)
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://" + net.JoinHostPort(host, port) + "/healthz")
	if err != nil {
		return fmt.Errorf("healthcheck: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("healthcheck: status %d", resp.StatusCode)
	}
	return nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envBool(key string, fallback bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		return fallback
	}
	return b
}
