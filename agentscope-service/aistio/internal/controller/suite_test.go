package controller_test

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/envtest"
	logf "sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"
	metricsserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"

	v1alpha1 "github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/controller"
)

var (
	cfg       *rest.Config
	k8sClient client.Client
	testEnv   *envtest.Environment

	// envtestReady is true when the envtest environment started successfully.
	envtestReady bool

	// mgrCancel cancels the background controller manager started in TestMain.
	mgrCancel context.CancelFunc
)

func TestMain(m *testing.M) {
	logf.SetLogger(zap.New(zap.WriteTo(os.Stderr)))

	testEnv = &envtest.Environment{
		CRDDirectoryPaths: []string{
			filepath.Join("..", "..", "config", "crd"),
		},
		ErrorIfCRDPathMissing: true,
	}

	var err error
	cfg, err = testEnv.Start()
	if err != nil {
		// envtest binaries not installed -- skip envtest tests but let unit tests run.
		fmt.Fprintf(os.Stderr, "envtest: skipping integration tests: %v\n", err)
	} else {
		if err := v1alpha1.AddToScheme(scheme.Scheme); err != nil {
			panic("failed to add v1alpha1 to scheme: " + err.Error())
		}

		k8sClient, err = client.New(cfg, client.Options{Scheme: scheme.Scheme})
		if err != nil {
			panic("failed to create k8s client: " + err.Error())
		}
		envtestReady = true

		// Start a controller manager with reconcilers so envtest tests
		// exercise real reconciliation logic, not just CRD CRUD.
		mgr, err := ctrl.NewManager(cfg, ctrl.Options{
			Scheme: scheme.Scheme,
			// Disable metrics and health listeners to avoid port conflicts in tests.
			Metrics:                metricsserver.Options{BindAddress: "0"},
			HealthProbeBindAddress: "0",
		})
		if err != nil {
			panic("failed to create manager: " + err.Error())
		}

		// Register AgentTeamReconciler (legacy mode -- nil Lifecycle).
		// The legacy path handles Pending->Running transitions without
		// external dependencies (no TaskStore, MessageRouter, etc.).
		if err := (&controller.AgentTeamReconciler{
			Client:   mgr.GetClient(),
			Scheme:   mgr.GetScheme(),
			Recorder: mgr.GetEventRecorderFor("agentteam-controller"),
			// Lifecycle is intentionally nil: the reconciler has a
			// legacyHandlePending fallback that works without it.
		}).SetupWithManager(mgr); err != nil {
			panic("failed to setup AgentTeamReconciler: " + err.Error())
		}

		// NOTE: AgentReconciler is NOT registered here because it requires
		// adapter.Get(runtime) to succeed, which needs a registered
		// DataPlaneAdapter. The adapter registry is populated via init()
		// in the adapter package, but the reconciler also needs a non-nil
		// Prober and Recorder to avoid panics during status updates.
		// Adding it would require importing the adapter package and
		// providing mock/stub infrastructure that is out of scope for
		// this envtest suite.

		var mgrCtx context.Context
		mgrCtx, mgrCancel = context.WithCancel(context.Background())
		go func() {
			if err := mgr.Start(mgrCtx); err != nil {
				panic("manager failed: " + err.Error())
			}
		}()
	}

	code := m.Run()

	if envtestReady {
		if mgrCancel != nil {
			mgrCancel()
		}
		if err := testEnv.Stop(); err != nil {
			fmt.Fprintf(os.Stderr, "envtest: failed to stop: %v\n", err)
		}
	}

	os.Exit(code)
}

// testContext returns a context with a timeout suitable for test assertions.
func testContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), testTimeout)
}

// skipIfNoEnvtest skips the test if envtest binaries are not available.
func skipIfNoEnvtest(t *testing.T) {
	t.Helper()
	if !envtestReady {
		t.Skip("envtest binaries not installed; run 'make envtest' and set KUBEBUILDER_ASSETS")
	}
}

// createNamespace creates a unique namespace for test isolation and returns its name.
func createNamespace(t *testing.T, prefix string) string {
	t.Helper()
	ctx, cancel := testContext()
	defer cancel()

	name := fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
	ns := &corev1.Namespace{
		ObjectMeta: metav1.ObjectMeta{Name: name},
	}
	if err := k8sClient.Create(ctx, ns); err != nil {
		t.Fatalf("failed to create namespace %s: %v", name, err)
	}
	t.Cleanup(func() {
		_ = k8sClient.Delete(context.Background(), ns)
	})
	return name
}
