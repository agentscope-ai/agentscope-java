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
	"encoding/json"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/anthropiccli"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/codex"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/openclaw"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/qoder"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider/qwenpaw"
	"github.com/spring-ai-alibaba/aistio/internal/version"
)

func main() {
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
		qoderBinary       string
		qwenPawBinary     string
		openClawBinary    string
		agentScopeBinary  string
		providerNames     string
		readyFile         string
		pollInterval      time.Duration
		heartbeatInterval time.Duration
		leaseTTL          time.Duration
	)
	flag.StringVar(&controlPlane, "control-plane", envOr("AISTIO_CONTROL_PLANE", "http://127.0.0.1:8080"), "aistiod base URL")
	flag.StringVar(&internalToken, "internal-token", envOr("AISTIO_INTERNAL_TOKEN", ""), "runtime host internal token")
	flag.StringVar(&tenant, "tenant", envOr("AISTIO_TENANT", "default"), "tenant")
	flag.StringVar(&namespace, "namespace", envOr("AISTIO_NAMESPACE", "default"), "namespace")
	flag.StringVar(&hostKey, "host-key", envOr("AISTIO_HOST_KEY", ""), "stable host identity (generated in state-root when omitted)")
	flag.StringVar(&poolName, "pool", envOr("AISTIO_RUNTIME_POOL", "coding-default"), "runtime pool")
	flag.IntVar(&capacity, "capacity", 1, "maximum concurrent executions")
	flag.StringVar(&workspaceRoot, "workspace-root", envOr("AISTIO_HOST_WORKSPACE_ROOT", "./data/runtime-host/workspaces"), "workspace root")
	flag.StringVar(&stateRoot, "state-root", envOr("AISTIO_HOST_STATE_ROOT", "./data/runtime-host/state"), "durable journal root")
	flag.StringVar(&codexBinary, "codex-binary", envOr("AISTIO_CODEX_BINARY", "codex"), "Codex CLI binary")
	flag.StringVar(&claudeBinary, "claude-binary", envOr("AISTIO_CLAUDE_BINARY", "claude"), "Claude Code CLI binary")
	flag.StringVar(&qoderBinary, "qoder-binary", envOr("AISTIO_QODER_BINARY", "qodercli"), "Qoder CLI binary")
	flag.StringVar(&qwenPawBinary, "qwenpaw-binary", envOr("AISTIO_QWENPAW_BINARY", "qwenpaw"), "QwenPaw CLI binary")
	flag.StringVar(&openClawBinary, "openclaw-binary", envOr("AISTIO_OPENCLAW_BINARY", "openclaw"), "OpenClaw CLI binary")
	flag.StringVar(&agentScopeBinary, "agentscope-binary", envOr("AISTIO_AGENTSCOPE_BINARY", "auto"), "AgentScope collaboration CLI binary, or auto")
	flag.StringVar(&providerNames, "providers", envOr("AISTIO_RUNTIME_PROVIDERS", "auto"),
		"providers to expose: auto, or a comma-separated list of codex,claude-code,qoder,qwenpaw,openclaw")
	flag.StringVar(&readyFile, "ready-file", envOr("AISTIO_HOST_READY_FILE", ""), "write registration readiness to this file")
	flag.DurationVar(&pollInterval, "poll-interval", 2*time.Second, "claim polling interval")
	flag.DurationVar(&heartbeatInterval, "heartbeat-interval", 15*time.Second, "host heartbeat interval")
	flag.DurationVar(&leaseTTL, "lease-ttl", 60*time.Second, "execution lease TTL")
	flag.Parse()
	var err error
	hostKey, err = runtimehost.ResolveHostKey(hostKey, stateRoot)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	if internalToken == "" || poolName == "" {
		fmt.Fprintln(os.Stderr, "--internal-token and --pool are required")
		os.Exit(2)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	if readyFile != "" {
		_ = os.Remove(readyFile)
		defer os.Remove(readyFile)
	}
	providers, err := configuredProviders(providerNames, codexBinary, claudeBinary, qoderBinary, qwenPawBinary, openClawBinary)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	agentScopeBinary = resolveAgentScopeBinary(agentScopeBinary)
	if agentScopeBinary == "" {
		slog.Warn("agentscope CLI was not found; shell-only providers cannot use task collaboration fallback")
	}
	engine := &runtimehost.Engine{
		Config: runtimehost.Config{
			Registration: runtimehost.Registration{
				Tenant: tenant, Namespace: namespace, HostKey: hostKey, PoolName: poolName,
				DaemonVersion: version.Version, Capacity: int32(capacity),
			},
			PollInterval: pollInterval, HeartbeatInterval: heartbeatInterval, LeaseTTL: leaseTTL,
			WorkspaceRoot: workspaceRoot, StateRoot: stateRoot,
			ControlPlane: controlPlane, CollaborationCLI: agentScopeBinary,
			CollaborationMCP: strings.TrimRight(controlPlane, "/") + "/mcp/collaboration",
		},
		Client:    &runtimehost.Client{BaseURL: controlPlane, InternalToken: internalToken},
		Providers: providers,
	}
	if readyFile != "" {
		engine.OnRegistered = func(host *controlmodel.RuntimeHost) error {
			return writeReadyFile(readyFile, host)
		}
	}
	slog.Info("starting aistio runtime host", "host", hostKey, "pool", poolName, "controlPlane", controlPlane)
	if err := engine.Run(ctx); err != nil {
		slog.Error("runtime host stopped", "error", err)
		os.Exit(1)
	}
}

func resolveAgentScopeBinary(configured string) string {
	configured = strings.TrimSpace(configured)
	if configured != "" && configured != "auto" {
		if path, err := exec.LookPath(configured); err == nil {
			absolute, _ := filepath.Abs(path)
			return absolute
		}
		return ""
	}
	if self, err := os.Executable(); err == nil {
		for _, name := range []string{"agentscope"} {
			candidate := filepath.Join(filepath.Dir(self), name)
			if info, statErr := os.Stat(candidate); statErr == nil && !info.IsDir() && info.Mode()&0o111 != 0 {
				return candidate
			}
		}
	}
	for _, name := range []string{"agentscope"} {
		if path, err := exec.LookPath(name); err == nil {
			absolute, _ := filepath.Abs(path)
			return absolute
		}
	}
	return ""
}

func writeReadyFile(path string, host *controlmodel.RuntimeHost) error {
	if host == nil {
		return fmt.Errorf("registered host is required")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.Marshal(map[string]any{
		"hostId": host.ID, "hostKey": host.HostKey, "registeredAt": time.Now().UTC(),
	})
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".ready-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if err = tmp.Chmod(0o600); err == nil {
		_, err = tmp.Write(append(data, '\n'))
	}
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

func configuredProviders(names, codexBinary, claudeBinary, qoderBinary, qwenPawBinary, openClawBinary string) (map[string]provider.Adapter, error) {
	providers := make(map[string]provider.Adapter)
	if strings.TrimSpace(names) == "auto" {
		candidates := []struct {
			name    string
			binary  string
			adapter provider.Adapter
		}{
			{name: "codex", binary: codexBinary, adapter: &codex.Adapter{Binary: codexBinary}},
			{name: "claude-code", binary: claudeBinary, adapter: &anthropiccli.Adapter{Binary: claudeBinary}},
			{name: "qoder", binary: qoderBinary, adapter: &qoder.Adapter{Binary: qoderBinary}},
			{name: "qwenpaw", binary: qwenPawBinary, adapter: &qwenpaw.Adapter{Binary: qwenPawBinary}},
			{name: "openclaw", binary: openClawBinary, adapter: &openclaw.Adapter{Binary: openClawBinary}},
		}
		for _, candidate := range candidates {
			if _, err := exec.LookPath(candidate.binary); err == nil {
				providers[candidate.name] = candidate.adapter
			}
		}
		if len(providers) == 0 {
			return nil, fmt.Errorf("no supported agent runtime was detected; install Codex, Claude Code, Qoder, QwenPaw, or OpenClaw, or set --providers explicitly")
		}
		return providers, nil
	}
	for _, raw := range strings.Split(names, ",") {
		switch name := strings.TrimSpace(raw); name {
		case "":
			continue
		case "codex":
			providers[name] = &codex.Adapter{Binary: codexBinary}
		case "claude-code":
			providers[name] = &anthropiccli.Adapter{Binary: claudeBinary}
		case "qoder":
			providers[name] = &qoder.Adapter{Binary: qoderBinary}
		case "qwenpaw":
			providers[name] = &qwenpaw.Adapter{Binary: qwenPawBinary}
		case "openclaw":
			providers[name] = &openclaw.Adapter{Binary: openClawBinary}
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
