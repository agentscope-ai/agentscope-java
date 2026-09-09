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

package provider

import (
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

const projectionMarker = ".agentscope-projection.json"

type skillProjectionMarker struct {
	Owner  string `json:"owner"`
	Source string `json:"source"`
}

type ownedSkillProjection struct {
	target string
	marker skillProjectionMarker
}

// ProjectSkills copies portable skills into a provider-native project skill
// directory for the lifetime of one process. Existing repository-owned skills
// are never overwritten. The returned cleanup only removes directories that
// still carry the matching AgentScope ownership marker.
func ProjectSkills(workspace, nativeRoot, owner string) (func(), error) {
	noop := func() {}
	if strings.TrimSpace(workspace) == "" || strings.TrimSpace(nativeRoot) == "" || strings.TrimSpace(owner) == "" {
		return nil, fmt.Errorf("workspace, native skill root, and projection owner are required")
	}
	if filepath.IsAbs(nativeRoot) || filepath.Clean(nativeRoot) == ".." ||
		strings.HasPrefix(filepath.Clean(nativeRoot), ".."+string(filepath.Separator)) {
		return nil, fmt.Errorf("native skill root must stay inside the workspace")
	}
	sourceRoot := filepath.Join(workspace, ".agentscope", "definition", "skills")
	entries, err := os.ReadDir(sourceRoot)
	if os.IsNotExist(err) {
		return noop, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read portable skills: %w", err)
	}
	targetRoot := filepath.Join(workspace, filepath.Clean(nativeRoot))
	created := make([]ownedSkillProjection, 0, len(entries))
	cleanup := func() {
		for _, projection := range created {
			marker, markerErr := readProjectionMarker(projection.target)
			if markerErr == nil && *marker == projection.marker {
				_ = os.RemoveAll(projection.target)
			}
		}
		removeEmptyParents(targetRoot, workspace)
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		source := filepath.Join(sourceRoot, entry.Name())
		if _, statErr := os.Stat(filepath.Join(source, "SKILL.md")); statErr != nil {
			if os.IsNotExist(statErr) {
				continue
			}
			cleanup()
			return nil, fmt.Errorf("inspect portable skill %q: %w", entry.Name(), statErr)
		}
		target := filepath.Join(targetRoot, entry.Name())
		if _, statErr := os.Lstat(target); statErr == nil {
			marker, markerErr := readProjectionMarker(target)
			expectedSource := filepath.ToSlash(filepath.Join(".agentscope", "definition", "skills", entry.Name()))
			if markerErr != nil || marker.Owner != owner || marker.Source != expectedSource {
				cleanup()
				return nil, fmt.Errorf("native skill %q already exists and is not managed by AgentScope", filepath.ToSlash(filepath.Join(nativeRoot, entry.Name())))
			}
			if removeErr := os.RemoveAll(target); removeErr != nil {
				cleanup()
				return nil, fmt.Errorf("replace stale native skill projection %q: %w", entry.Name(), removeErr)
			}
		} else if !os.IsNotExist(statErr) {
			cleanup()
			return nil, fmt.Errorf("inspect native skill projection %q: %w", entry.Name(), statErr)
		}
		if err = copySkillDirectory(source, target); err != nil {
			_ = os.RemoveAll(target)
			cleanup()
			return nil, err
		}
		marker := skillProjectionMarker{Owner: owner, Source: filepath.ToSlash(filepath.Join(".agentscope", "definition", "skills", entry.Name()))}
		data, marshalErr := json.Marshal(marker)
		if marshalErr != nil {
			cleanup()
			return nil, marshalErr
		}
		if err = os.WriteFile(filepath.Join(target, projectionMarker), data, 0o600); err != nil {
			_ = os.RemoveAll(target)
			cleanup()
			return nil, fmt.Errorf("mark native skill projection %q: %w", entry.Name(), err)
		}
		created = append(created, ownedSkillProjection{target: target, marker: marker})
	}
	return cleanup, nil
}

func copySkillDirectory(source, target string) error {
	if err := os.MkdirAll(target, 0o750); err != nil {
		return fmt.Errorf("create native skill projection: %w", err)
	}
	return filepath.WalkDir(source, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		relative, err := filepath.Rel(source, path)
		if err != nil || relative == "." {
			return err
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("portable skill contains unsupported symbolic link %q", filepath.ToSlash(relative))
		}
		destination := filepath.Join(target, relative)
		if entry.IsDir() {
			return os.MkdirAll(destination, 0o750)
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		input, err := os.Open(path)
		if err != nil {
			return err
		}
		mode := fs.FileMode(0o640)
		if info.Mode()&0o111 != 0 {
			mode = 0o750
		}
		output, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
		if err != nil {
			_ = input.Close()
			return err
		}
		_, copyErr := io.Copy(output, input)
		inputErr := input.Close()
		closeErr := output.Close()
		if copyErr != nil {
			return copyErr
		}
		if inputErr != nil {
			return inputErr
		}
		return closeErr
	})
}

func readProjectionMarker(target string) (*skillProjectionMarker, error) {
	data, err := os.ReadFile(filepath.Join(target, projectionMarker))
	if err != nil {
		return nil, err
	}
	var marker skillProjectionMarker
	if err = json.Unmarshal(data, &marker); err != nil {
		return nil, err
	}
	return &marker, nil
}

func removeEmptyParents(path, stop string) {
	stop, _ = filepath.Abs(stop)
	for current, _ := filepath.Abs(path); current != "" && current != stop; current = filepath.Dir(current) {
		if err := os.Remove(current); err != nil {
			return
		}
	}
}
