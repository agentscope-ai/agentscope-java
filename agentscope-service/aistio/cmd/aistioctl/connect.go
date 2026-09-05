// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/spf13/cobra"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost"
	"golang.org/x/term"
)

func connectCmd() *cobra.Command {
	var server, credential, providers, runtimeHostBinary, pool, workspaceRoot, stateRoot, username string
	var capacity int
	var noStart, foreground bool
	cmd := &cobra.Command{
		Use:   "connect [server]",
		Short: "Connect this machine as an AgentScope Runtime Host",
		Long: "Detects installed coding agents, writes an owner-only local configuration, " +
			"and starts the Runtime Host daemon. The credential is read from the environment or prompted once.",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			configPath, err := localRuntimeConfigPath()
			if err != nil {
				return err
			}
			config := defaultLocalRuntimeConfig(configPath)
			var previous *localRuntimeConfig
			if existing, loadErr := loadLocalRuntimeConfig(configPath); loadErr == nil {
				config = *existing
				previous = existing
			}
			requestedServer := server
			if len(args) == 1 {
				requestedServer = args[0]
			}
			config.ControlPlane, err = resolveControlPlane(cmd.Context(), requestedServer)
			if err != nil {
				return err
			}
			config.Version = localRuntimeConfigVersion
			if cmd.Root().PersistentFlags().Changed("tenant") || config.Tenant == "" {
				config.Tenant = tenant
			}
			if cmd.Root().PersistentFlags().Changed("namespace") || config.Namespace == "" {
				config.Namespace = namespace
			}
			if pool != "" {
				config.Pool = pool
			}
			if capacity > 0 {
				config.Capacity = capacity
			}
			if workspaceRoot != "" {
				config.WorkspaceRoot, err = filepath.Abs(workspaceRoot)
				if err != nil {
					return fmt.Errorf("resolve workspace root: %w", err)
				}
			}
			if stateRoot != "" {
				config.StateRoot, err = filepath.Abs(stateRoot)
				if err != nil {
					return fmt.Errorf("resolve state root: %w", err)
				}
			}
			if runtimeHostBinary != "" {
				config.RuntimeHostBinary, err = filepath.Abs(runtimeHostBinary)
				if err != nil {
					return fmt.Errorf("resolve Runtime Host binary: %w", err)
				}
			}
			config.Providers, err = discoverRuntimeProviders(cmd.Context(), providers)
			if err != nil {
				return err
			}
			config.Credential = firstNonBlank(credential, os.Getenv("AGENTSCOPE_RUNTIME_TOKEN"),
				os.Getenv("AISTIO_INTERNAL_TOKEN"), os.Getenv("BUILDER_INTERNAL_TOKEN"))
			platformToken := apiToken
			if config.Credential == "" && platformToken == "" {
				platformToken, err = promptPlatformLogin(cmd.Context(), config.ControlPlane, username)
			}
			if config.Credential == "" && platformToken != "" {
				hostKey, identityErr := runtimehost.ResolveHostKey("", config.StateRoot)
				if identityErr != nil {
					return identityErr
				}
				config.Credential, err = enrollRuntimeHostCredential(cmd.Context(), config.ControlPlane,
					platformToken, hostKey, config.Tenant, config.Namespace)
			}
			if err != nil {
				return err
			}
			if previous != nil && !noStart {
				if _, running := localRuntimeProcessState(previous); running {
					if _, err := stopLocalRuntime(previous, 10*time.Second); err != nil {
						return err
					}
				}
			}
			if err := saveLocalRuntimeConfig(configPath, &config); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Connected this machine to %s\n", config.ControlPlane)
			fmt.Fprintf(cmd.OutOrStdout(), "Configuration: %s\n", configPath)
			fmt.Fprintln(cmd.OutOrStdout(), "Detected runtimes:")
			for _, provider := range config.Providers {
				fmt.Fprintf(cmd.OutOrStdout(), "  %-12s %s (%s)\n", provider.Name, provider.Version, provider.Binary)
			}
			if noStart {
				fmt.Fprintln(cmd.OutOrStdout(), "Run `agentscope runtime start` when you are ready.")
				return nil
			}
			return startLocalRuntime(cmd, &config, foreground)
		},
	}
	cmd.Flags().StringVar(&server, "server", "", "AgentScope control-plane URL (auto-detected locally when omitted)")
	cmd.Flags().StringVar(&credential, "token", "", "Runtime enrollment credential (prefer AGENTSCOPE_RUNTIME_TOKEN)")
	cmd.Flags().StringVar(&username, "username", os.Getenv("AGENTSCOPE_USERNAME"), "AgentScope username for interactive connection")
	cmd.Flags().StringVar(&providers, "providers", "auto", "Providers to expose: auto or codex,claude-code,qoder,qwenpaw,openclaw")
	cmd.Flags().StringVar(&runtimeHostBinary, "runtime-host-binary", "", "Path to aistio-runtime-host")
	cmd.Flags().StringVar(&pool, "pool", "", "Runtime pool (default coding-default)")
	cmd.Flags().IntVar(&capacity, "capacity", 0, "Maximum concurrent executions (default 1)")
	cmd.Flags().StringVar(&workspaceRoot, "workspace-root", "", "Task workspace root")
	cmd.Flags().StringVar(&stateRoot, "state-root", "", "Runtime state root")
	cmd.Flags().BoolVar(&noStart, "no-start", false, "Save configuration without starting the daemon")
	cmd.Flags().BoolVar(&foreground, "foreground", false, "Run the daemon in the foreground")
	return cmd
}

func resolveControlPlane(ctx context.Context, configured string) (string, error) {
	configured = firstNonBlank(configured, os.Getenv("AGENTSCOPE_SERVER"), os.Getenv("AISTIO_CONTROL_PLANE"))
	if configured != "" {
		return validateControlPlane(configured)
	}
	for _, candidate := range []string{
		"http://127.0.0.1:18080",
		"http://127.0.0.1:8080",
		"http://127.0.0.1:8081",
	} {
		probeCtx, cancel := context.WithTimeout(ctx, 800*time.Millisecond)
		req, _ := http.NewRequestWithContext(probeCtx, http.MethodGet, candidate+"/healthz", nil)
		response, err := http.DefaultClient.Do(req)
		cancel()
		if err == nil {
			_ = response.Body.Close()
			if response.StatusCode >= 200 && response.StatusCode < 300 {
				return candidate, nil
			}
		}
	}
	return "", fmt.Errorf("cannot discover an AgentScope server; pass it to `agentscope connect SERVER`")
}

func validateControlPlane(value string) (string, error) {
	value = strings.TrimRight(strings.TrimSpace(value), "/")
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return "", fmt.Errorf("invalid AgentScope server URL %q", value)
	}
	return value, nil
}

func promptPlatformLogin(ctx context.Context, server, configuredUsername string) (string, error) {
	if !term.IsTerminal(int(os.Stdin.Fd())) {
		return "", fmt.Errorf("authentication is required; set AGENTSCOPE_API_TOKEN or AGENTSCOPE_RUNTIME_TOKEN, or rerun in an interactive terminal")
	}
	username := strings.TrimSpace(configuredUsername)
	if username == "" {
		fmt.Fprint(os.Stderr, "AgentScope username: ")
		value, err := bufio.NewReader(os.Stdin).ReadString('\n')
		if err != nil {
			return "", fmt.Errorf("read AgentScope username: %w", err)
		}
		username = strings.TrimSpace(value)
	}
	if username == "" {
		return "", fmt.Errorf("AgentScope username is required")
	}
	fmt.Fprint(os.Stderr, "AgentScope password: ")
	data, err := term.ReadPassword(int(os.Stdin.Fd()))
	fmt.Fprintln(os.Stderr)
	if err != nil {
		return "", fmt.Errorf("read AgentScope password: %w", err)
	}
	if len(data) == 0 {
		return "", fmt.Errorf("AgentScope password is required")
	}
	return loginPlatformUser(ctx, server, username, string(data))
}

func loginPlatformUser(ctx context.Context, server, username, password string) (string, error) {
	payload, err := json.Marshal(map[string]string{"username": username, "password": password})
	if err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(server, "/")+"/api/auth/login", bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return "", fmt.Errorf("sign in to AgentScope: %w", err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", fmt.Errorf("sign in to AgentScope: status=%d", response.StatusCode)
	}
	var body struct {
		Token string `json:"token"`
	}
	if json.Unmarshal(data, &body) != nil || body.Token == "" {
		return "", fmt.Errorf("sign in to AgentScope: response did not contain a token")
	}
	return body.Token, nil
}

func enrollRuntimeHostCredential(ctx context.Context, server, platformToken, hostKey, tenant, namespace string) (string, error) {
	payload, err := json.Marshal(map[string]string{
		"hostKey": hostKey, "tenant": tenant, "namespace": namespace,
	})
	if err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(server, "/")+"/api/v1/runtime-host-enrollments", bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+platformToken)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return "", fmt.Errorf("create Runtime Host enrollment: %w", err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", fmt.Errorf("create Runtime Host enrollment: status=%d body=%s", response.StatusCode, strings.TrimSpace(string(data)))
	}
	var body struct {
		RuntimeToken string `json:"runtimeToken"`
	}
	if json.Unmarshal(data, &body) != nil || body.RuntimeToken == "" {
		return "", fmt.Errorf("create Runtime Host enrollment: response did not contain a runtime token")
	}
	return body.RuntimeToken, nil
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if value = strings.TrimSpace(value); value != "" {
			return value
		}
	}
	return ""
}
