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

package artifact

import (
	"context"
	"io"
	"strings"
	"testing"
)

func TestLocalProviderIntegrityAndTraversal(t *testing.T) {
	provider := &LocalProvider{Root: t.TempDir()}
	info, err := provider.Put(context.Background(), "tenant/ns/object", strings.NewReader("shared result"))
	if err != nil || info.Size != 13 || !strings.HasPrefix(info.Checksum, "sha256:") {
		t.Fatalf("put: info=%+v err=%v", info, err)
	}
	reader, opened, err := provider.Open(context.Background(), "tenant/ns/object")
	if err != nil || opened != info {
		t.Fatalf("open: info=%+v err=%v", opened, err)
	}
	defer reader.Close()
	data, _ := io.ReadAll(reader)
	if string(data) != "shared result" {
		t.Fatalf("unexpected payload %q", data)
	}
	if _, err := provider.Put(context.Background(), "../../escape", strings.NewReader("bad")); err == nil {
		t.Fatal("expected traversal key to fail")
	}
}
