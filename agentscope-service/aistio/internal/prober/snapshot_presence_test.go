// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package prober

import (
	"encoding/json"
	"testing"
)

func TestSnapshotDistinguishesMeasuredZeroFromMissingTelemetry(t *testing.T) {
	for _, tc := range []struct {
		body     string
		measured bool
	}{
		{`{"id":"s","contextPressure":0,"tokenUsage":{"promptTokens":0,"completionTokens":0}}`, true},
		{`{"id":"s"}`, false},
		{`{"id":"s","contextPressure":null,"tokenUsage":null}`, false},
	} {
		var snapshot SessionSnapshot
		if err := json.Unmarshal([]byte(tc.body), &snapshot); err != nil {
			t.Fatal(err)
		}
		if snapshot.ContextPressureReported != tc.measured || (snapshot.TokenUsage != nil) != tc.measured {
			t.Fatalf("presence lost for %s: %+v", tc.body, snapshot)
		}
	}
}
