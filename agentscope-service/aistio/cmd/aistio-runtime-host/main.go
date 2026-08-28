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

package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/claudecode"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/codex"
	"github.com/spring-ai-alibaba/aistio/internal/version"
)

func main() {
	hostname, _ := os.Hostname()
	var (
		controlPlane      string
		internalToken     string
		tenant            string
		namespace         string
		hostKey           string
		poolName          string
		capacity          int
		workspaceRoot     string
		stateRoot         string
		codexBinary       string
		claudeBinary      string
		providerNames     string
		pollInterval      time.Duration
		heartbeatInterval time.Duration
		leaseTTL          time.Duration
	)
	flag.StringVar(&controlPlane, "control-plane", envOr("AISTIO_CONTROL_PLANE", "http://127.0.0.1:8080"), "aistiod base URL")
	flag.StringVar(&internalToken, "internal-token", envOr("AISTIO_INTERNAL_TOKEN", ""), "runtime host internal token")
	flag.StringVar(&tenant, "tenant", envOr("AISTIO_TENANT", "default"), "tenant")
	flag.StringVar(&namespace, "namespace", envOr("AISTIO_NAMESPACE", "default"), "namespace")
	flag.StringVar(&hostKey, "host-key", envOr("AISTIO_HOST_KEY", hostname), "stable host identity")
	flag.StringVar(&poolName, "pool", envOr("AISTIO_RUNTIME_POOL", "coding-default"), "runtime pool")
	flag.IntVar(&capacity, "capacity", 1, "maximum concurrent executions")
	flag.StringVar(&workspaceRoot, "workspace-root", envOr("AISTIO_HOST_WORKSPACE_ROOT", "./data/runtime-host/workspaces"), "workspace root")
	flag.StringVar(&stateRoot, "state-root", envOr("AISTIO_HOST_STATE_ROOT", "./data/runtime-host/state"), "durable journal root")
	flag.StringVar(&codexBinary, "codex-binary", envOr("AISTIO_CODEX_BINARY", "codex"), "Codex CLI binary")
	flag.StringVar(&claudeBinary, "claude-binary", envOr("AISTIO_CLAUDE_BINARY", "claude"), "Claude Code CLI binary")
	flag.StringVar(&providerNames, "providers", envOr("AISTIO_RUNTIME_PROVIDERS", "codex"),
		"comma-separated providers installed on this Host: codex,claude-code")
	flag.DurationVar(&pollInterval, "poll-interval", 2*time.Second, "claim polling interval")
	flag.DurationVar(&heartbeatInterval, "heartbeat-interval", 15*time.Second, "host heartbeat interval")
	flag.DurationVar(&leaseTTL, "lease-ttl", 30*time.Second, "execution lease TTL")
	flag.Parse()
	if internalToken == "" || hostKey == "" || poolName == "" {
		fmt.Fprintln(os.Stderr, "--internal-token, --host-key, and --pool are required")
		os.Exit(2)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	providers, err := configuredProviders(providerNames, codexBinary, claudeBinary)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	engine := &runtimehost.Engine{
		Config: runtimehost.Config{
			Registration: runtimehost.Registration{
				Tenant: tenant, Namespace: namespace, HostKey: hostKey, PoolName: poolName,
				DaemonVersion: version.Version, Capacity: int32(capacity),
			},
			PollInterval: pollInterval, HeartbeatInterval: heartbeatInterval, LeaseTTL: leaseTTL,
			WorkspaceRoot: workspaceRoot, StateRoot: stateRoot,
		},
		Client:    &runtimehost.Client{BaseURL: controlPlane, InternalToken: internalToken},
		Providers: providers,
	}
	slog.Info("starting aistio runtime host", "host", hostKey, "pool", poolName, "controlPlane", controlPlane)
	if err := engine.Run(ctx); err != nil {
		slog.Error("runtime host stopped", "error", err)
		os.Exit(1)
	}
}

func configuredProviders(names, codexBinary, claudeBinary string) (map[string]provider.Adapter, error) {
	providers := make(map[string]provider.Adapter)
	for _, raw := range strings.Split(names, ",") {
		switch name := strings.TrimSpace(raw); name {
		case "":
			continue
		case "codex":
			providers[name] = &codex.Adapter{Binary: codexBinary}
		case "claude-code":
			providers[name] = &claudecode.Adapter{Binary: claudeBinary}
		default:
			return nil, fmt.Errorf("unsupported runtime provider %q", name)
		}
	}
	if len(providers) == 0 {
		return nil, fmt.Errorf("at least one runtime provider is required")
	}
	return providers, nil
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
