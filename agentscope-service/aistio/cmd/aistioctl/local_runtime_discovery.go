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
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

type providerCandidate struct {
	name  string
	paths []string
}

func runtimeProviderCandidates() []providerCandidate {
	home, _ := os.UserHomeDir()
	candidates := []providerCandidate{
		{name: "codex", paths: []string{os.Getenv("AISTIO_CODEX_BINARY"), "codex"}},
		{name: "claude-code", paths: []string{os.Getenv("AISTIO_CLAUDE_BINARY"), "claude"}},
		{name: "qoder", paths: []string{os.Getenv("AISTIO_QODER_BINARY"), "qodercli"}},
		{name: "qwenpaw", paths: []string{os.Getenv("AISTIO_QWENPAW_BINARY"), "qwenpaw"}},
		{name: "openclaw", paths: []string{os.Getenv("AISTIO_OPENCLAW_BINARY"), "openclaw"}},
	}
	if runtime.GOOS == "darwin" {
		candidates[0].paths = append(candidates[0].paths,
			"/Applications/ChatGPT.app/Contents/Resources/codex")
	}
	if home != "" {
		candidates[0].paths = append(candidates[0].paths, filepath.Join(home, ".local", "bin", "codex"))
		candidates[1].paths = append(candidates[1].paths, filepath.Join(home, ".local", "bin", "claude"))
		candidates[2].paths = append(candidates[2].paths, filepath.Join(home, ".local", "bin", "qodercli"))
		candidates[3].paths = append(candidates[3].paths, filepath.Join(home, ".local", "bin", "qwenpaw"))
		candidates[4].paths = append(candidates[4].paths, filepath.Join(home, ".local", "bin", "openclaw"))
	}
	return candidates
}

func discoverRuntimeProviders(ctx context.Context, selected string) ([]localRuntimeProvider, error) {
	wanted := map[string]bool{}
	if value := strings.TrimSpace(selected); value != "" && value != "auto" {
		for _, item := range strings.Split(value, ",") {
			name := strings.TrimSpace(item)
			if name != "" {
				wanted[name] = true
			}
		}
	}
	var providers []localRuntimeProvider
	known := map[string]bool{}
	for _, candidate := range runtimeProviderCandidates() {
		known[candidate.name] = true
		if len(wanted) > 0 && !wanted[candidate.name] {
			continue
		}
		binary := firstRunnableBinary(candidate.paths)
		if binary == "" {
			continue
		}
		versionCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
		output, err := runtimeBinaryVersion(versionCtx, binary)
		cancel()
		if err != nil {
			continue
		}
		providers = append(providers, localRuntimeProvider{
			Name: candidate.name, Binary: binary, Version: conciseVersion(string(output)),
		})
	}
	for name := range wanted {
		if !known[name] {
			return nil, fmt.Errorf("unsupported runtime provider %q", name)
		}
		found := false
		for _, provider := range providers {
			if provider.Name == name {
				found = true
				break
			}
		}
		if !found {
			return nil, fmt.Errorf("runtime provider %q was requested but its CLI is unavailable", name)
		}
	}
	if len(providers) == 0 {
		return nil, fmt.Errorf("no supported agent CLI detected; install Codex, Claude Code, Qoder, QwenPaw, or OpenClaw first")
	}
	sort.Slice(providers, func(i, j int) bool { return providers[i].Name < providers[j].Name })
	return providers, nil
}

// A real file is used instead of CombinedOutput's pipe. Some Node-based CLIs
// leave helper processes holding inherited pipe descriptors after the parent
// is cancelled, which would otherwise make discovery hang past its timeout.
func runtimeBinaryVersion(ctx context.Context, binary string) ([]byte, error) {
	output, err := os.CreateTemp("", "agentscope-runtime-version-*")
	if err != nil {
		return nil, err
	}
	name := output.Name()
	defer func() { _ = os.Remove(name) }()
	command := exec.CommandContext(ctx, binary, "--version")
	command.Stdout, command.Stderr = output, output
	runErr := command.Run()
	if _, err := output.Seek(0, 0); err != nil {
		_ = output.Close()
		return nil, err
	}
	data, readErr := os.ReadFile(name)
	_ = output.Close()
	if runErr != nil {
		return data, runErr
	}
	return data, readErr
}

func firstRunnableBinary(candidates []string) string {
	seen := map[string]bool{}
	for _, candidate := range candidates {
		if candidate == "" || seen[candidate] {
			continue
		}
		seen[candidate] = true
		path, err := exec.LookPath(candidate)
		if err != nil {
			continue
		}
		absolute, err := filepath.Abs(path)
		if err == nil {
			path = absolute
		}
		return path
	}
	return ""
}

func conciseVersion(output string) string {
	for _, line := range strings.Split(strings.TrimSpace(output), "\n") {
		line = strings.TrimSpace(line)
		if line != "" && !strings.HasPrefix(strings.ToLower(line), "warning") && !strings.HasPrefix(strings.ToLower(line), "skipped") {
			return line
		}
	}
	return strings.TrimSpace(output)
}
