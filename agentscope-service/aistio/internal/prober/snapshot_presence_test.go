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
