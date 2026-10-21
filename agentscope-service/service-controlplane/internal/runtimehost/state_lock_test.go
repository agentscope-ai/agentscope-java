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

import "testing"

func TestStateRootLockExcludesDuplicateDaemonAndReleasesOnClose(t *testing.T) {
	root := t.TempDir()
	first, err := LockStateRoot(root)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = first.Close() })
	if duplicate, err := LockStateRoot(root); err == nil {
		_ = duplicate.Close()
		t.Fatal("duplicate daemon can use the same journal")
	}
	other, err := LockStateRoot(t.TempDir())
	if err != nil {
		t.Fatal("independent Host state directory was blocked", err)
	}
	_ = other.Close()
	if err = first.Close(); err != nil {
		t.Fatal(err)
	}
	restarted, err := LockStateRoot(root)
	if err != nil {
		t.Fatal("stale lock file blocked a restart", err)
	}
	_ = restarted.Close()
}
