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

package codex

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/pelletier/go-toml/v2"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

const subagentProjectionHeader = "# AgentScope generated subagent; source: .agentscope/definition/subagents/\n"

// Project only files owned by AgentScope. Cleanup checks exact bytes so a
// repository/user edit made while the child runs is preserved.
func projectSubagents(workspace string, items []provider.NativeSubagent) (func(), error) {
	created := map[string][]byte{}
	root := filepath.Join(workspace, ".agentscope", "native", "codex", "agents")
	cleanup := func() {
		for filename, expected := range created {
			actual, err := os.ReadFile(filename)
			if err == nil && bytes.Equal(actual, expected) {
				_ = os.Remove(filename)
			}
		}
		_ = os.Remove(root)
		_ = os.Remove(filepath.Dir(root))
	}
	for _, dir := range []string{filepath.Join(workspace, ".agentscope"), filepath.Join(workspace, ".agentscope", "native"), filepath.Join(workspace, ".agentscope", "native", "codex"), root} {
		info, err := os.Lstat(dir)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return nil, fmt.Errorf("native Codex agents directory must be a real directory: %s", dir)
		}
	}
	entries, err := os.ReadDir(root)
	if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	// Remove projections left by an interrupted process, including definitions
	// removed since its run. Unmarked repository files are never touched.
	for _, entry := range entries {
		if !entry.Type().IsRegular() || !strings.HasSuffix(entry.Name(), ".toml") {
			continue
		}
		filename := filepath.Join(root, entry.Name())
		data, readErr := os.ReadFile(filename)
		if readErr != nil {
			return nil, readErr
		}
		if bytes.HasPrefix(data, []byte(subagentProjectionHeader)) {
			if err := os.Remove(filename); err != nil {
				return nil, err
			}
		}
	}
	if len(items) == 0 {
		return cleanup, nil
	}
	if err := os.MkdirAll(root, 0o750); err != nil {
		return nil, err
	}
	for _, item := range items {
		definition := map[string]string{"name": item.Name, "description": item.Description, "developer_instructions": item.Prompt}
		if item.Model != "" {
			definition["model"] = item.Model
		}
		data, err := toml.Marshal(definition)
		if err != nil {
			cleanup()
			return nil, err
		}
		data = append([]byte(subagentProjectionHeader), data...)
		filename := filepath.Join(root, item.Name+".toml")
		file, err := os.OpenFile(filename, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o640)
		if err != nil {
			cleanup()
			return nil, fmt.Errorf("native Codex subagent %q already exists or cannot be created: %w", item.Name, err)
		}
		created[filename] = data
		_, writeErr := file.Write(data)
		closeErr := file.Close()
		if writeErr != nil || closeErr != nil {
			_ = os.Remove(filename)
			cleanup()
			return nil, fmt.Errorf("write native Codex subagent %q: %v %v", item.Name, writeErr, closeErr)
		}
	}
	return cleanup, nil
}
