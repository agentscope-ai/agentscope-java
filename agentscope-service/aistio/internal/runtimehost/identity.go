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
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/google/uuid"
)

const hostIdentityFilename = "host.id"

// ResolveHostKey returns an explicitly configured identity, or creates a
// durable UUID scoped to this daemon state directory. A hostname is unsuitable
// here because it aliases containers and changes across machine renames.
func ResolveHostKey(configured, stateRoot string) (string, error) {
	if value := strings.TrimSpace(configured); value != "" {
		return value, nil
	}
	if strings.TrimSpace(stateRoot) == "" {
		return "", fmt.Errorf("runtime host state root is required")
	}
	path := filepath.Join(stateRoot, hostIdentityFilename)
	if value, err := readHostIdentity(path); err == nil || !errors.Is(err, os.ErrNotExist) {
		return value, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return "", fmt.Errorf("create runtime host state directory: %w", err)
	}
	value := uuid.NewString()
	tmp, err := os.CreateTemp(filepath.Dir(path), ".host-id-*")
	if err != nil {
		return "", fmt.Errorf("create runtime host identity: %w", err)
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if err = tmp.Chmod(0o600); err == nil {
		_, err = tmp.WriteString(value + "\n")
	}
	if err == nil {
		err = tmp.Sync()
	}
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return "", fmt.Errorf("persist runtime host identity: %w", err)
	}
	// Link installs the identity without replacing one concurrently created by
	// another daemon process using the same state directory.
	if err = os.Link(tmpName, path); err != nil {
		if errors.Is(err, os.ErrExist) {
			return readHostIdentity(path)
		}
		return "", fmt.Errorf("install runtime host identity: %w", err)
	}
	return value, nil
}

func readHostIdentity(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	value := strings.TrimSpace(string(data))
	if _, err = uuid.Parse(value); err != nil {
		return "", fmt.Errorf("invalid runtime host identity in %s: %w", path, err)
	}
	return value, nil
}
