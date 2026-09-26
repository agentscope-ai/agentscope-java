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
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const localRuntimeConfigVersion = 1

type localRuntimeProvider struct {
	Name    string `json:"name"`
	Binary  string `json:"binary"`
	Version string `json:"version,omitempty"`
}

type localRuntimeConfig struct {
	Version           int                    `json:"version"`
	ControlPlane      string                 `json:"controlPlane"`
	Credential        string                 `json:"credential"`
	Tenant            string                 `json:"tenant"`
	Namespace         string                 `json:"namespace"`
	Pool              string                 `json:"pool"`
	Capacity          int                    `json:"capacity"`
	WorkspaceRoot     string                 `json:"workspaceRoot"`
	StateRoot         string                 `json:"stateRoot"`
	RuntimeHostBinary string                 `json:"runtimeHostBinary,omitempty"`
	Providers         []localRuntimeProvider `json:"providers"`
}

func localRuntimeConfigPath() (string, error) {
	if configured := strings.TrimSpace(os.Getenv("AGENTSCOPE_RUNTIME_CONFIG")); configured != "" {
		return filepath.Abs(configured)
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve home directory: %w", err)
	}
	return filepath.Join(home, ".agentscope", "runtime-host", "config.json"), nil
}

func defaultLocalRuntimeConfig(path string) localRuntimeConfig {
	root := filepath.Dir(path)
	return localRuntimeConfig{
		Version:       localRuntimeConfigVersion,
		Tenant:        "default",
		Namespace:     "default",
		Pool:          "coding-default",
		Capacity:      1,
		WorkspaceRoot: filepath.Join(root, "workspaces"),
		StateRoot:     filepath.Join(root, "state"),
	}
}

func loadLocalRuntimeConfig(path string) (*localRuntimeConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, fmt.Errorf("local runtime is not connected; run `agentscope connect` first")
		}
		return nil, fmt.Errorf("read runtime config: %w", err)
	}
	var config localRuntimeConfig
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, fmt.Errorf("decode runtime config: %w", err)
	}
	if config.Version != localRuntimeConfigVersion {
		return nil, fmt.Errorf("unsupported runtime config version %d", config.Version)
	}
	if strings.TrimSpace(config.ControlPlane) == "" || strings.TrimSpace(config.Credential) == "" {
		return nil, fmt.Errorf("runtime config is incomplete; rerun `agentscope connect`")
	}
	if len(config.Providers) == 0 {
		return nil, fmt.Errorf("runtime config has no providers; install a supported agent CLI and rerun `agentscope connect`")
	}
	return &config, nil
}

func saveLocalRuntimeConfig(path string, config *localRuntimeConfig) error {
	if config == nil {
		return fmt.Errorf("runtime config is required")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fmt.Errorf("create runtime config directory: %w", err)
	}
	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return fmt.Errorf("encode runtime config: %w", err)
	}
	data = append(data, '\n')
	tmp, err := os.CreateTemp(filepath.Dir(path), ".config-*")
	if err != nil {
		return fmt.Errorf("create temporary runtime config: %w", err)
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if err = tmp.Chmod(0o600); err == nil {
		_, err = tmp.Write(data)
	}
	if err == nil {
		err = tmp.Sync()
	}
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return fmt.Errorf("write runtime config: %w", err)
	}
	if err := os.Rename(tmpName, path); err != nil {
		return fmt.Errorf("install runtime config: %w", err)
	}
	return nil
}

func localRuntimePIDPath(config *localRuntimeConfig) string {
	return filepath.Join(config.StateRoot, "daemon.pid")
}

func localRuntimeLogPath(config *localRuntimeConfig) string {
	return filepath.Join(filepath.Dir(config.StateRoot), "daemon.log")
}

func localRuntimeReadyPath(config *localRuntimeConfig) string {
	return filepath.Join(config.StateRoot, "daemon.ready")
}
