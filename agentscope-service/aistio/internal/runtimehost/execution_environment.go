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

package runtimehost

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

const definitionDirectory = ".agentscope/definition"

// MaterializeDefinition is the provider-neutral execution-environment layer.
// It keeps portable Workspace content separate from a checked-out repository;
// adapters can then add provider-native projections without changing the
// control-plane definition.
func MaterializeDefinition(workspace string, definition *provider.AgentDefinition) (string, error) {
	if definition == nil {
		return "", nil
	}
	if strings.TrimSpace(workspace) == "" {
		return "", fmt.Errorf("workspace is required to materialize Agent definition")
	}
	container := filepath.Join(workspace, ".agentscope")
	if info, err := os.Lstat(container); err == nil && info.Mode()&os.ModeSymlink != 0 {
		return "", fmt.Errorf("Agent definition container must not be a symbolic link")
	} else if err != nil && !os.IsNotExist(err) {
		return "", err
	}
	root := filepath.Join(container, "definition")
	if err := os.RemoveAll(root); err != nil {
		return "", fmt.Errorf("reset Agent definition directory: %w", err)
	}
	if len(definition.Files) == 0 {
		return "", nil
	}
	for name, content := range definition.Files {
		clean := filepath.Clean(filepath.FromSlash(strings.TrimSpace(name)))
		if clean == "." || filepath.IsAbs(clean) || clean == ".." ||
			strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
			return "", fmt.Errorf("invalid Agent definition file path %q", name)
		}
		target := filepath.Join(root, clean)
		if err := os.MkdirAll(filepath.Dir(target), 0o750); err != nil {
			return "", fmt.Errorf("create Agent definition directory: %w", err)
		}
		if err := os.WriteFile(target, []byte(content), 0o640); err != nil {
			return "", fmt.Errorf("write Agent definition file %q: %w", name, err)
		}
	}
	return filepath.ToSlash(definitionDirectory), nil
}
