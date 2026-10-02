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
	"fmt"
	"time"

	"cel.dev/cel-go/cel"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

const maxExpressionLength = 4096

type DefinitionSpec struct {
	Nodes []DefinitionNode `json:"nodes"`
	Edges []DefinitionEdge `json:"edges,omitempty"`
}

type DefinitionNode struct {
	Key              string                                `json:"key"`
	Type             controlmodel.RunNodeType              `json:"type"`
	Role             string                                `json:"role,omitempty"`
	IssueMode        string                                `json:"issueMode,omitempty"`
	AgentID          string                                `json:"agentId,omitempty"`
	TeamRef          string                                `json:"teamRef,omitempty"`
	Condition        string                                `json:"condition,omitempty"`
	Input            map[string]string                     `json:"input,omitempty"`
	TimeoutSeconds   int64                                 `json:"timeoutSeconds,omitempty"`
	Retry            RetrySpec                             `json:"retry,omitempty"`
	FailurePolicy    string                                `json:"failurePolicy,omitempty"`
	Join             JoinSpec                              `json:"join,omitempty"`
	Timer            TimerSpec                             `json:"timer,omitempty"`
	SignalName       string                                `json:"signalName,omitempty"`
	Approval         ApprovalSpec                          `json:"approval,omitempty"`
	DefinitionRevID  string                                `json:"definitionRevisionId,omitempty"`
	RuntimeCandidate *controlmodel.RuntimeBindingCandidate `json:"runtimeCandidate,omitempty"`
}

type DefinitionEdge struct {
	From      string                      `json:"from"`
	To        string                      `json:"to"`
	On        []controlmodel.RunNodeState `json:"on,omitempty"`
	Condition string                      `json:"condition,omitempty"`
	Ordinal   int32                       `json:"ordinal,omitempty"`
}

type RetrySpec struct {
	MaxAttempts    int32 `json:"maxAttempts,omitempty"`
	BackoffSeconds int64 `json:"backoffSeconds,omitempty"`
}
type JoinSpec struct {
	Mode   string `json:"mode,omitempty"`
	Quorum int32  `json:"quorum,omitempty"`
}
type TimerSpec struct {
	At              *time.Time `json:"at,omitempty"`
	DurationSeconds int64      `json:"durationSeconds,omitempty"`
}
type ApprovalSpec struct {
	ApproverType controlmodel.AssigneeType `json:"approverType,omitempty"`
	ApproverRef  string                    `json:"approverRef,omitempty"`
	Prompt       string                    `json:"prompt,omitempty"`
}

type CEL struct {
	env            *cel.Env
	Timeout        time.Duration
	MaxOutputBytes int
}

func NewCEL() (*CEL, error) {
	env, err := cel.NewEnv(cel.Variable("run", cel.DynType), cel.Variable("issue", cel.DynType), cel.Variable("trigger", cel.DynType), cel.Variable("nodes", cel.DynType))
	if err != nil {
		return nil, err
	}
	return &CEL{env: env, Timeout: 50 * time.Millisecond, MaxOutputBytes: 1 << 20}, nil
}

func (c *CEL) Compile(expr string) error {
	if len(expr) > maxExpressionLength {
		return fmt.Errorf("CEL expression exceeds %d bytes", maxExpressionLength)
	}
	if expr == "" {
		return nil
	}
	_, issues := c.env.Compile(expr)
	if issues != nil && issues.Err() != nil {
		return issues.Err()
	}
	return nil
}

func (c *CEL) Eval(ctx context.Context, expr string, vars map[string]any) (any, error) {
	if err := c.Compile(expr); err != nil {
		return nil, err
	}
	ast, issues := c.env.Compile(expr)
	if issues != nil && issues.Err() != nil {
		return nil, issues.Err()
	}
	program, err := c.env.Program(ast)
	if err != nil {
		return nil, err
	}
	timeout := c.Timeout
	if timeout <= 0 {
		timeout = 50 * time.Millisecond
	}
	evalCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	value, _, err := program.ContextEval(evalCtx, vars)
	if err != nil {
		return nil, err
	}
	native := value.Value()
	encoded, err := json.Marshal(native)
	if err != nil {
		return nil, err
	}
	limit := c.MaxOutputBytes
	if limit <= 0 {
		limit = 1 << 20
	}
	if len(encoded) > limit {
		return nil, fmt.Errorf("CEL output exceeds %d bytes", limit)
	}
	return native, nil
}

func ParseAndValidateSpec(raw json.RawMessage, evaluator *CEL) (*DefinitionSpec, error) {
	var spec DefinitionSpec
	if err := json.Unmarshal(raw, &spec); err != nil {
		return nil, fmt.Errorf("decode definition spec: %w", err)
	}
	if len(spec.Nodes) == 0 {
		return nil, fmt.Errorf("definition requires at least one node")
	}
	if evaluator == nil {
		var err error
		evaluator, err = NewCEL()
		if err != nil {
			return nil, err
		}
	}
	keys := map[string]DefinitionNode{}
	validType := map[controlmodel.RunNodeType]bool{controlmodel.RunNodeAgent: true, controlmodel.RunNodeTeam: true, controlmodel.RunNodeApproval: true, controlmodel.RunNodeCondition: true, controlmodel.RunNodeJoin: true, controlmodel.RunNodeTimer: true, controlmodel.RunNodeSignal: true, controlmodel.RunNodeSubrun: true}
	for _, n := range spec.Nodes {
		if n.Key == "" || keys[n.Key].Key != "" {
			return nil, fmt.Errorf("node keys must be non-empty and unique: %q", n.Key)
		}
		if !validType[n.Type] {
			return nil, fmt.Errorf("node %q has unsupported type %q", n.Key, n.Type)
		}
		if n.Type == controlmodel.RunNodeAgent {
			if _, err := uuid.Parse(n.AgentID); err != nil {
				return nil, fmt.Errorf("agent node %q requires a valid agentId", n.Key)
			}
		}
		if n.Type == controlmodel.RunNodeTeam && n.TeamRef == "" {
			return nil, fmt.Errorf("team node %q requires teamRef", n.Key)
		}
		if n.IssueMode != "" && n.IssueMode != "inherit" && n.IssueMode != "child" {
			return nil, fmt.Errorf("node %q has invalid issueMode", n.Key)
		}
		if n.FailurePolicy != "" && n.FailurePolicy != "fail_fast" && n.FailurePolicy != "continue" && n.FailurePolicy != "partial_success" {
			return nil, fmt.Errorf("node %q has invalid failurePolicy", n.Key)
		}
		if n.TimeoutSeconds < 0 || n.Retry.MaxAttempts < 0 || n.Retry.BackoffSeconds < 0 {
			return nil, fmt.Errorf("node %q timeout and retry values cannot be negative", n.Key)
		}
		if n.Type == controlmodel.RunNodeJoin {
			mode := n.Join.Mode
			if mode == "" {
				mode = "all"
			}
			if mode != "all" && mode != "any" && mode != "quorum" {
				return nil, fmt.Errorf("join node %q has invalid mode", n.Key)
			}
			if mode == "quorum" && n.Join.Quorum <= 0 {
				return nil, fmt.Errorf("join node %q requires a positive quorum", n.Key)
			}
		}
		if n.Type == controlmodel.RunNodeSignal && n.SignalName == "" {
			return nil, fmt.Errorf("signal node %q requires signalName", n.Key)
		}
		if n.Type == controlmodel.RunNodeApproval && n.Approval.ApproverRef == "" {
			return nil, fmt.Errorf("approval node %q requires approverRef", n.Key)
		}
		if n.Type == controlmodel.RunNodeTimer && n.Timer.At == nil && n.Timer.DurationSeconds <= 0 {
			return nil, fmt.Errorf("timer node %q requires at or positive durationSeconds", n.Key)
		}
		if n.Type == controlmodel.RunNodeSubrun && n.DefinitionRevID == "" {
			return nil, fmt.Errorf("subrun node %q requires definitionRevisionId", n.Key)
		}
		if err := evaluator.Compile(n.Condition); err != nil {
			return nil, fmt.Errorf("node %q condition: %w", n.Key, err)
		}
		for name, expr := range n.Input {
			if err := evaluator.Compile(expr); err != nil {
				return nil, fmt.Errorf("node %q input %q: %w", n.Key, name, err)
			}
		}
		if n.Type == controlmodel.RunNodeTeam {
			if id, err := uuid.Parse(n.TeamRef); err != nil || id == uuid.Nil {
				return nil, fmt.Errorf("team node %q requires a valid teamRef", n.Key)
			}
		}
		if n.Type == controlmodel.RunNodeSubrun {
			if id, err := uuid.Parse(n.DefinitionRevID); err != nil || id == uuid.Nil {
				return nil, fmt.Errorf("subrun node %q requires a valid revision ID", n.Key)
			}
		}
		keys[n.Key] = n
	}
	indegree := map[string]int{}
	next := map[string][]string{}
	for _, e := range spec.Edges {
		if keys[e.From].Key == "" || keys[e.To].Key == "" {
			return nil, fmt.Errorf("edge %q -> %q references an unknown node", e.From, e.To)
		}
		if e.From == e.To {
			return nil, fmt.Errorf("self edge on %q", e.From)
		}
		if err := evaluator.Compile(e.Condition); err != nil {
			return nil, fmt.Errorf("edge %q -> %q: %w", e.From, e.To, err)
		}
		for _, state := range e.On {
			if !controlmodel.IsRunNodeTerminal(state) {
				return nil, fmt.Errorf("edge %q -> %q requires terminal on states", e.From, e.To)
			}
		}
		indegree[e.To]++
		next[e.From] = append(next[e.From], e.To)
	}
	for _, n := range spec.Nodes {
		if n.Type == controlmodel.RunNodeJoin && n.Join.Mode == "quorum" && int(n.Join.Quorum) > indegree[n.Key] {
			return nil, fmt.Errorf("join %q quorum exceeds incoming paths", n.Key)
		}
	}
	queue := []string{}
	for key := range keys {
		if indegree[key] == 0 {
			queue = append(queue, key)
		}
	}
	visited := 0
	for len(queue) > 0 {
		key := queue[0]
		queue = queue[1:]
		visited++
		for _, to := range next[key] {
			indegree[to]--
			if indegree[to] == 0 {
				queue = append(queue, to)
			}
		}
	}
	if visited != len(keys) {
		return nil, fmt.Errorf("definition graph must be acyclic")
	}
	return &spec, nil
}
