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

package product

import (
	"strings"
	"testing"
)

func TestBuildAndParseSubagentMarkdown(t *testing.T) {
	max := 12
	md := buildSubagentMarkdown(subagentUpsertReq{
		Description:   "Reviews code",
		Model:         "qwen3-max",
		MaxIters:      &max,
		Tools:         []string{"read_file", "grep_files"},
		WorkspaceMode: "isolated",
		InlineBody:    "You are a reviewer.\n",
	})
	if !strings.HasPrefix(md, "---\n") {
		t.Fatalf("expected YAML frontmatter, got: %q", md[:40])
	}
	if !strings.Contains(md, "description: Reviews code") {
		t.Fatalf("missing description: %s", md)
	}
	if !strings.Contains(md, "maxIters: 12") {
		t.Fatalf("missing maxIters: %s", md)
	}
	info := parseSubagentMarkdown(md)
	if info["description"] != "Reviews code" {
		t.Fatalf("parse description: %#v", info["description"])
	}
	if info["model"] != "qwen3-max" {
		t.Fatalf("parse model: %#v", info["model"])
	}
}

func TestProductToHarnessToolName(t *testing.T) {
	if got := productToHarnessToolName("bash"); got != "execute" {
		t.Fatalf("bash -> %s", got)
	}
	if got := productToHarnessToolName("read"); got != "read_file" {
		t.Fatalf("read -> %s", got)
	}
	if got := productToHarnessToolName("web_fetch"); got != "web_fetch" {
		t.Fatalf("web_fetch -> %s", got)
	}
}
