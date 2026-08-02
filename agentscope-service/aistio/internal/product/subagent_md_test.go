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
