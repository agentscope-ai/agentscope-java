// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package runtimehost

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// LockStateRoot excludes another daemon before identity resolution or provider
// startup. Closing the file or exiting the process releases the OS lock. The
// lock file remains in place so a later process cannot lock a different inode.
func LockStateRoot(root string) (*os.File, error) {
	if strings.TrimSpace(root) == "" {
		return nil, fmt.Errorf("runtime host state root is required")
	}
	if err := os.MkdirAll(root, 0o750); err != nil {
		return nil, err
	}
	file, err := os.OpenFile(filepath.Join(root, ".daemon.lock"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	if err = lockStateFile(file); err != nil {
		_ = file.Close()
		return nil, fmt.Errorf("runtime host state directory is already in use or cannot be locked: %w", err)
	}
	return file, nil
}
