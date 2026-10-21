/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package runtimeprotocol

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	path := filepath.Join("..", "..", "..", "service-common", "src", "test", "resources", "runtime-protocol", "v1", name+".json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestJavaFixturesRoundTrip(t *testing.T) {
	requests := map[string]func() any{
		"bootstrap":               func() any { return &RuntimeSessionBootstrap{} },
		"admission":               func() any { return &RuntimeSessionAdmission{} },
		"lifecycle":               func() any { return &RuntimeSessionLifecycle{} },
		"lifecycle-receipt":       func() any { return &RuntimeLifecycleReceipt{} },
		"ready":                   func() any { return &RuntimeSessionReady{} },
		"command-receipt":         func() any { return &RuntimeCommandReceipt{} },
		"turn":                    func() any { return &TurnCommand{} },
		"execution-request":       func() any { return &RuntimeExecutionRequest{} },
		"execution-grant":         func() any { return &RuntimeExecutionGrant{} },
		"execution-grant-overlay": func() any { return &RuntimeExecutionGrant{} },
		"interaction":             func() any { return &InteractionCommand{} },
		"interaction-receipt":     func() any { return &RuntimeInteractionReceipt{} },
		"fact":                    func() any { return &RuntimeFact{} },
		"fact-receipt":            func() any { return &RuntimeFactReceipt{} },
		"tool-task":               func() any { return &ToolTask{} },
		"tool-result":             func() any { return &ToolResult{} },
		"usage":                   func() any { return &UsageReceipt{} },
	}
	for name, create := range requests {
		t.Run(name, func(t *testing.T) {
			data := fixture(t, name)
			request := create()
			if err := Decode(bytes.NewReader(data), request); err != nil {
				t.Fatal(err)
			}
			encoded, err := json.Marshal(request)
			if err != nil {
				t.Fatal(err)
			}
			var expected, actual any
			if err := json.Unmarshal(data, &expected); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal(encoded, &actual); err != nil {
				t.Fatal(err)
			}
			expectedJSON, _ := json.Marshal(expected)
			actualJSON, _ := json.Marshal(actual)
			if !bytes.Equal(expectedJSON, actualJSON) {
				t.Fatalf("wire fields changed:\nexpected %s\nactual %s", expectedJSON, actualJSON)
			}
		})
	}
}

func TestFutureVersionRejected(t *testing.T) {
	data := bytes.Replace(fixture(t, "turn"), []byte(`"version": 1`), []byte(`"version": 2`), 1)
	err := Decode(bytes.NewReader(data), &TurnCommand{})
	if err == nil || !strings.Contains(err.Error(), "unsupported runtime protocol version") {
		t.Fatalf("expected a version error, got %v", err)
	}
}
