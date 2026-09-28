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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

type localRuntimeReadyState struct {
	HostID  string `json:"hostId"`
	HostKey string `json:"hostKey"`
}

func localRuntimeStartCmd() *cobra.Command {
	var foreground bool
	cmd := &cobra.Command{
		Use:   "start",
		Short: "Start the local Runtime Host daemon",
		RunE: func(cmd *cobra.Command, _ []string) error {
			config, err := configuredLocalRuntime()
			if err != nil {
				return err
			}
			return startLocalRuntime(cmd, config, foreground)
		},
	}
	cmd.Flags().BoolVar(&foreground, "foreground", false, "Run in the foreground")
	return cmd
}

func localRuntimeStopCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "stop",
		Short: "Stop the local Runtime Host daemon",
		RunE: func(cmd *cobra.Command, _ []string) error {
			config, err := configuredLocalRuntime()
			if err != nil {
				return err
			}
			stopped, err := stopLocalRuntime(config, 10*time.Second)
			if err != nil {
				return err
			}
			if stopped {
				fmt.Fprintln(cmd.OutOrStdout(), "AgentScope Runtime Host stopped.")
			} else {
				fmt.Fprintln(cmd.OutOrStdout(), "AgentScope Runtime Host is not running.")
			}
			return nil
		},
	}
}

func localRuntimeRestartCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "restart",
		Short: "Restart the local Runtime Host daemon",
		RunE: func(cmd *cobra.Command, _ []string) error {
			config, err := configuredLocalRuntimeForRestart()
			if err != nil {
				return err
			}
			if _, err := stopLocalRuntime(config, 10*time.Second); err != nil {
				return err
			}
			return startLocalRuntime(cmd, config, false)
		},
	}
}

func localRuntimeStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "status",
		Short: "Show local Runtime Host status",
		RunE: func(cmd *cobra.Command, _ []string) error {
			path, err := localRuntimeConfigPath()
			if err != nil {
				return err
			}
			config, err := loadLocalRuntimeConfig(path)
			if err != nil {
				fmt.Fprintln(cmd.OutOrStdout(), "Status: not connected")
				fmt.Fprintln(cmd.OutOrStdout(), "Run `agentscope connect` to configure this machine.")
				return nil
			}
			pid, running := localRuntimeProcessState(config)
			state := "stopped"
			if running {
				state = "running"
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Status:        %s\n", state)
			if running {
				fmt.Fprintf(cmd.OutOrStdout(), "PID:           %d\n", pid)
			}
			if ready := readLocalRuntimeReadyState(config); ready != nil {
				fmt.Fprintf(cmd.OutOrStdout(), "Host ID:       %s\n", ready.HostID)
				fmt.Fprintf(cmd.OutOrStdout(), "Host key:      %s\n", ready.HostKey)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Server:        %s\n", config.ControlPlane)
			fmt.Fprintf(cmd.OutOrStdout(), "Scope:         %s / %s\n", config.Tenant, config.Namespace)
			fmt.Fprintf(cmd.OutOrStdout(), "Pool:          %s\n", config.Pool)
			fmt.Fprintf(cmd.OutOrStdout(), "Providers:     %s\n", providerNames(config.Providers))
			fmt.Fprintf(cmd.OutOrStdout(), "Configuration: %s\n", path)
			fmt.Fprintf(cmd.OutOrStdout(), "Logs:          %s\n", localRuntimeLogPath(config))
			return nil
		},
	}
}

func localRuntimeLogsCmd() *cobra.Command {
	var follow bool
	var lines int
	cmd := &cobra.Command{
		Use:   "logs",
		Short: "Show local Runtime Host logs",
		RunE: func(cmd *cobra.Command, _ []string) error {
			config, err := configuredLocalRuntime()
			if err != nil {
				return err
			}
			return streamLocalRuntimeLogs(cmd, localRuntimeLogPath(config), lines, follow)
		},
	}
	cmd.Flags().BoolVarP(&follow, "follow", "f", false, "Follow new log output")
	cmd.Flags().IntVar(&lines, "lines", 50, "Number of existing lines to show")
	return cmd
}

func localRuntimeProbeCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "probe",
		Short: "Detect installed coding-agent CLIs",
		RunE: func(cmd *cobra.Command, _ []string) error {
			providers, err := discoverRuntimeProviders(cmd.Context(), "auto")
			if err != nil {
				return err
			}
			for _, provider := range providers {
				fmt.Fprintf(cmd.OutOrStdout(), "%-12s %s\n  %s\n", provider.Name, provider.Version, provider.Binary)
			}
			return nil
		},
	}
}

func configuredLocalRuntime() (*localRuntimeConfig, error) {
	path, err := localRuntimeConfigPath()
	if err != nil {
		return nil, err
	}
	return loadLocalRuntimeConfig(path)
}

// Kept as a named seam so lifecycle tests can exercise restart without Cobra globals.
func configuredLocalRuntimeForRestart() (*localRuntimeConfig, error) {
	return configuredLocalRuntime()
}

func startLocalRuntime(cmd *cobra.Command, config *localRuntimeConfig, foreground bool) error {
	if pid, running := localRuntimeProcessState(config); running {
		fmt.Fprintf(cmd.OutOrStdout(), "AgentScope Runtime Host is already running (PID %d).\n", pid)
		return nil
	}
	binary, err := resolveRuntimeHostBinary(config)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(config.StateRoot, 0o700); err != nil {
		return fmt.Errorf("create runtime state directory: %w", err)
	}
	if err := os.MkdirAll(config.WorkspaceRoot, 0o750); err != nil {
		return fmt.Errorf("create runtime workspace directory: %w", err)
	}
	_ = os.Remove(localRuntimeReadyPath(config))
	child := exec.Command(binary, "--capacity", strconv.Itoa(config.Capacity))
	child.Env = runtimeHostEnvironment(config)
	if foreground {
		child.Stdin, child.Stdout, child.Stderr = cmd.InOrStdin(), cmd.OutOrStdout(), cmd.ErrOrStderr()
		if err := child.Start(); err != nil {
			return fmt.Errorf("start Runtime Host: %w", err)
		}
		if err := writeLocalRuntimePID(config, child.Process.Pid); err != nil {
			_ = child.Process.Kill()
			return err
		}
		defer removeLocalRuntimePID(config)
		defer os.Remove(localRuntimeReadyPath(config))
		return child.Wait()
	}
	logFile, err := os.OpenFile(localRuntimeLogPath(config), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return fmt.Errorf("open runtime log: %w", err)
	}
	defer logFile.Close()
	child.Stdin = nil
	child.Stdout, child.Stderr = logFile, logFile
	configureDetachedProcess(child)
	if err := child.Start(); err != nil {
		return fmt.Errorf("start Runtime Host: %w", err)
	}
	pid := child.Process.Pid
	if err := writeLocalRuntimePID(config, pid); err != nil {
		_ = child.Process.Kill()
		return err
	}
	exited := make(chan error, 1)
	go func() {
		exited <- child.Wait()
		if readLocalRuntimePID(config) == pid {
			removeLocalRuntimePID(config)
			_ = os.Remove(localRuntimeReadyPath(config))
		}
	}()
	if err := waitForLocalRuntimeReady(config, exited, 15*time.Second); err != nil {
		_, _ = stopLocalRuntime(config, time.Second)
		if detail := lastLocalRuntimeLogLines(localRuntimeLogPath(config), 4); detail != "" {
			return fmt.Errorf("%w\n%s", err, detail)
		}
		return err
	}
	fmt.Fprintf(cmd.OutOrStdout(), "AgentScope Runtime Host started (PID %d).\n", pid)
	fmt.Fprintf(cmd.OutOrStdout(), "Logs: %s\n", localRuntimeLogPath(config))
	return nil
}

func stopLocalRuntime(config *localRuntimeConfig, timeout time.Duration) (bool, error) {
	pid, running := localRuntimeProcessState(config)
	if !running {
		removeLocalRuntimePID(config)
		_ = os.Remove(localRuntimeReadyPath(config))
		return false, nil
	}
	process, err := os.FindProcess(pid)
	if err != nil {
		return false, fmt.Errorf("find Runtime Host process %d: %w", pid, err)
	}
	if err := terminateLocalProcess(process); err != nil && !errors.Is(err, os.ErrProcessDone) {
		return false, fmt.Errorf("stop Runtime Host process %d: %w", pid, err)
	}
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if !localProcessAlive(pid) {
			removeLocalRuntimePID(config)
			_ = os.Remove(localRuntimeReadyPath(config))
			return true, nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err := process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
		return false, fmt.Errorf("force stop Runtime Host process %d: %w", pid, err)
	}
	removeLocalRuntimePID(config)
	_ = os.Remove(localRuntimeReadyPath(config))
	return true, nil
}

func resolveRuntimeHostBinary(config *localRuntimeConfig) (string, error) {
	candidates := []string{config.RuntimeHostBinary, os.Getenv("AGENTSCOPE_RUNTIME_HOST_BINARY")}
	if executable, err := os.Executable(); err == nil {
		candidates = append(candidates, filepath.Join(filepath.Dir(executable), "aistio-runtime-host"))
	}
	candidates = append(candidates, "aistio-runtime-host", filepath.Join("bin", "aistio-runtime-host"))
	if path := firstRunnableBinary(candidates); path != "" {
		return path, nil
	}
	return "", fmt.Errorf("aistio-runtime-host was not found; install it next to the agentscope CLI or pass `agentscope connect --runtime-host-binary PATH`")
}

func runtimeHostEnvironment(config *localRuntimeConfig) []string {
	overrides := []string{
		"AISTIO_CONTROL_PLANE=" + config.ControlPlane,
		"AISTIO_INTERNAL_TOKEN=" + config.Credential,
		"AISTIO_TENANT=" + config.Tenant,
		"AISTIO_NAMESPACE=" + config.Namespace,
		"AISTIO_RUNTIME_POOL=" + config.Pool,
		"AISTIO_RUNTIME_PROVIDERS=" + providerNames(config.Providers),
		"AISTIO_HOST_WORKSPACE_ROOT=" + config.WorkspaceRoot,
		"AISTIO_HOST_STATE_ROOT=" + config.StateRoot,
		"AISTIO_HOST_READY_FILE=" + localRuntimeReadyPath(config),
	}
	for _, provider := range config.Providers {
		switch provider.Name {
		case "codex":
			overrides = append(overrides, "AISTIO_CODEX_BINARY="+provider.Binary)
		case "claude-code":
			overrides = append(overrides, "AISTIO_CLAUDE_BINARY="+provider.Binary)
		case "qoder":
			overrides = append(overrides, "AISTIO_QODER_BINARY="+provider.Binary)
		case "qwenpaw":
			overrides = append(overrides, "AISTIO_QWENPAW_BINARY="+provider.Binary)
		case "openclaw":
			overrides = append(overrides, "AISTIO_OPENCLAW_BINARY="+provider.Binary)
		}
	}
	replaced := map[string]bool{
		"AGENTSCOPE_API_TOKEN":     true,
		"AGENTSCOPE_RUNTIME_TOKEN": true,
		"BUILDER_INTERNAL_TOKEN":   true,
	}
	for _, item := range overrides {
		key, _, _ := strings.Cut(item, "=")
		replaced[key] = true
	}
	environment := make([]string, 0, len(os.Environ())+len(overrides))
	for _, item := range os.Environ() {
		key, _, _ := strings.Cut(item, "=")
		if !replaced[key] {
			environment = append(environment, item)
		}
	}
	environment = append(environment, overrides...)
	return environment
}

func providerNames(providers []localRuntimeProvider) string {
	names := make([]string, 0, len(providers))
	for _, provider := range providers {
		names = append(names, provider.Name)
	}
	return strings.Join(names, ",")
}

func writeLocalRuntimePID(config *localRuntimeConfig, pid int) error {
	if err := os.MkdirAll(config.StateRoot, 0o700); err != nil {
		return fmt.Errorf("create runtime state directory: %w", err)
	}
	if err := os.WriteFile(localRuntimePIDPath(config), []byte(strconv.Itoa(pid)+"\n"), 0o600); err != nil {
		return fmt.Errorf("write Runtime Host PID: %w", err)
	}
	return nil
}

func readLocalRuntimePID(config *localRuntimeConfig) int {
	data, err := os.ReadFile(localRuntimePIDPath(config))
	if err != nil {
		return 0
	}
	pid, _ := strconv.Atoi(strings.TrimSpace(string(data)))
	return pid
}

func removeLocalRuntimePID(config *localRuntimeConfig) {
	_ = os.Remove(localRuntimePIDPath(config))
}

func localRuntimeProcessState(config *localRuntimeConfig) (int, bool) {
	pid := readLocalRuntimePID(config)
	return pid, pid > 0 && localProcessAlive(pid)
}

func waitForLocalRuntimeReady(config *localRuntimeConfig, exited <-chan error, timeout time.Duration) error {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		if ready := readLocalRuntimeReadyState(config); ready != nil {
			return nil
		}
		select {
		case err := <-exited:
			removeLocalRuntimePID(config)
			if err != nil {
				return fmt.Errorf("Runtime Host exited before registering: %w", err)
			}
			return fmt.Errorf("Runtime Host exited before registering")
		case <-timer.C:
			return fmt.Errorf("Runtime Host did not register within %s; inspect %s", timeout, localRuntimeLogPath(config))
		case <-ticker.C:
		}
	}
}

func readLocalRuntimeReadyState(config *localRuntimeConfig) *localRuntimeReadyState {
	data, err := os.ReadFile(localRuntimeReadyPath(config))
	if err != nil {
		return nil
	}
	var state localRuntimeReadyState
	if json.Unmarshal(data, &state) != nil || state.HostID == "" {
		return nil
	}
	return &state
}

func lastLocalRuntimeLogLines(path string, count int) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) > count {
		lines = lines[len(lines)-count:]
	}
	return strings.Join(lines, "\n")
}

func streamLocalRuntimeLogs(cmd *cobra.Command, path string, lineCount int, follow bool) error {
	if lineCount < 0 {
		return fmt.Errorf("lines must be non-negative")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("no Runtime Host log exists at %s", path)
		}
		return err
	}
	lines := strings.Split(string(data), "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	start := len(lines) - lineCount
	if start < 0 {
		start = 0
	}
	for _, line := range lines[start:] {
		fmt.Fprintln(cmd.OutOrStdout(), line)
	}
	if !follow {
		return nil
	}
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	if _, err := file.Seek(int64(len(data)), io.SeekStart); err != nil {
		return err
	}
	reader := bufio.NewReader(file)
	for {
		line, readErr := reader.ReadString('\n')
		if line != "" {
			fmt.Fprint(cmd.OutOrStdout(), line)
		}
		if readErr == nil {
			continue
		}
		if !errors.Is(readErr, io.EOF) {
			return readErr
		}
		select {
		case <-cmd.Context().Done():
			return nil
		case <-time.After(250 * time.Millisecond):
		}
		reader.Reset(file)
	}
}
