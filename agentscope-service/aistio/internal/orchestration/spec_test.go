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

package orchestration

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestDefinitionValidationCompilesCELAndRejectsCycles(t *testing.T) {
	evaluator, err := NewCEL()
	if err != nil {
		t.Fatal(err)
	}
	valid := json.RawMessage(`{"nodes":[{"key":"route","type":"condition","condition":"run.input.enabled == true"},{"key":"done","type":"join","join":{"mode":"any"}}],"edges":[{"from":"route","to":"done","condition":"nodes.route.status == 'succeeded'"}]}`)
	if _, err = ParseAndValidateSpec(valid, evaluator); err != nil {
		t.Fatalf("valid DAG rejected: %v", err)
	}
	cycle := json.RawMessage(`{"nodes":[{"key":"a","type":"condition"},{"key":"b","type":"condition"}],"edges":[{"from":"a","to":"b"},{"from":"b","to":"a"}]}`)
	if _, err = ParseAndValidateSpec(cycle, evaluator); err == nil || !strings.Contains(err.Error(), "acyclic") {
		t.Fatalf("cycle was not rejected: %v", err)
	}
	badCEL := json.RawMessage(`{"nodes":[{"key":"a","type":"condition","condition":"open('/etc/passwd')"}]}`)
	if _, err = ParseAndValidateSpec(badCEL, evaluator); err == nil {
		t.Fatal("unknown CEL function was accepted")
	}
}

func TestCELLimitsOutputAndExpressionLength(t *testing.T) {
	evaluator, err := NewCEL()
	if err != nil {
		t.Fatal(err)
	}
	if err = evaluator.Compile(strings.Repeat("x", maxExpressionLength+1)); err == nil {
		t.Fatal("oversized expression accepted")
	}
	evaluator.MaxOutputBytes = 2
	if _, err = evaluator.Eval(context.Background(), `"large"`, map[string]any{}); err == nil {
		t.Fatal("oversized output accepted")
	}
	evaluator.Timeout = time.Nanosecond
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = evaluator.Eval(ctx, `true`, map[string]any{}); err == nil {
		t.Fatal("cancelled CEL evaluation accepted")
	}
}
