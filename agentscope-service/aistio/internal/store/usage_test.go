// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package store

import (
	"encoding/json"
	"testing"
)

func TestAttemptUsageAndMergeUsage(t *testing.T) {
	fromResult := AttemptUsage(nil, json.RawMessage(`{"output":"ok","usage":{"totalTokens":7,"tokens":{"input":4},"provider":"one"}}`))
	merged := MergeUsage(json.RawMessage(`{"totalTokens":3,"tokens":{"input":2,"output":1},"provider":"old"}`), fromResult)
	var got struct {
		TotalTokens int64 `json:"totalTokens"`
		Tokens      struct {
			Input  int64 `json:"input"`
			Output int64 `json:"output"`
		} `json:"tokens"`
		Provider string `json:"provider"`
	}
	if err := json.Unmarshal(merged, &got); err != nil {
		t.Fatal(err)
	}
	if got.TotalTokens != 10 || got.Tokens.Input != 6 || got.Tokens.Output != 1 || got.Provider != "one" {
		t.Fatalf("unexpected merged usage: %s", merged)
	}
	explicit := AttemptUsage(json.RawMessage(`{"totalTokens":9}`), json.RawMessage(`{"usage":{"totalTokens":100}}`))
	if string(explicit) != `{"totalTokens":9}` {
		t.Fatalf("explicit usage did not win: %s", explicit)
	}
}
