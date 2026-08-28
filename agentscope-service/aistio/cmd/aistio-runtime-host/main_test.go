// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package main

import "testing"

func TestConfiguredProviders(t *testing.T) {
	providers, err := configuredProviders("codex, claude-code", "/bin/codex", "/bin/claude")
	if err != nil {
		t.Fatal(err)
	}
	if providers["codex"] == nil || providers["claude-code"] == nil || len(providers) != 2 {
		t.Fatalf("providers=%v", providers)
	}
	if _, err := configuredProviders("unknown", "codex", "claude"); err == nil {
		t.Fatal("expected unsupported provider error")
	}
}
