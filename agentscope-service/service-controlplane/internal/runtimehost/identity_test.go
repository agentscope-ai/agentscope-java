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
	"os"
	"path/filepath"
	"testing"

	"github.com/google/uuid"
)

func TestResolveHostKeyPersistsGeneratedIdentity(t *testing.T) {
	root := t.TempDir()
	first, err := ResolveHostKey("", root)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = uuid.Parse(first); err != nil {
		t.Fatalf("generated host identity is not a UUID: %q", first)
	}
	second, err := ResolveHostKey("", root)
	if err != nil || second != first {
		t.Fatalf("identity was not stable: first=%q second=%q err=%v", first, second, err)
	}
	info, err := os.Stat(filepath.Join(root, hostIdentityFilename))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("identity permissions=%v", info.Mode().Perm())
	}
}

func TestResolveHostKeyHonorsExplicitValue(t *testing.T) {
	value, err := ResolveHostKey("developer-laptop", t.TempDir())
	if err != nil || value != "developer-laptop" {
		t.Fatalf("explicit identity=%q err=%v", value, err)
	}
}

func TestResolveHostKeyRejectsCorruptIdentity(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, hostIdentityFilename), []byte("not-a-uuid\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ResolveHostKey("", root); err == nil {
		t.Fatal("corrupt identity was silently replaced")
	}
}
