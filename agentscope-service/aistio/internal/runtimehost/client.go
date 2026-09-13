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

package runtimehost

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

var ErrNoWork = errors.New("runtime host: no work")

type Client struct {
	BaseURL       string
	InternalToken string
	HTTPClient    *http.Client
	attemptTokens sync.Map
}

func (c *Client) RestoreAttemptToken(attemptID uuid.UUID, token string) {
	if attemptID != uuid.Nil && token != "" {
		c.attemptTokens.Store(attemptID, token)
	}
}

type ControlPlaneClient interface {
	Register(context.Context, Registration) (*controlmodel.RuntimeHost, error)
	Heartbeat(context.Context, *controlmodel.RuntimeHost, int32, json.RawMessage) (*controlmodel.RuntimeHost, error)
	Claim(context.Context, *controlmodel.RuntimeHost, string, string, time.Duration) (*ClaimedWork, error)
	Prepare(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt) error
	Start(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt, string, string) error
	Renew(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt, time.Duration) error
	Checkpoint(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt, string, json.RawMessage) error
	Complete(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt, json.RawMessage, json.RawMessage) error
	Fail(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt, string, string, json.RawMessage) error
	Cancelled(context.Context, uuid.UUID, *controlmodel.ExecutionAttempt) error
}

type Registration struct {
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	HostKey       string          `json:"hostKey"`
	PoolName      string          `json:"poolName"`
	DaemonVersion string          `json:"daemonVersion,omitempty"`
	OS            string          `json:"os,omitempty"`
	Arch          string          `json:"arch,omitempty"`
	Labels        json.RawMessage `json:"labels,omitempty"`
	Capabilities  json.RawMessage `json:"capabilities,omitempty"`
	Capacity      int32           `json:"capacity"`
}

type ClaimedWork struct {
	Task               *controlmodel.AgentTask                `json:"task"`
	Context            *collaboration.ContextEnvelope         `json:"context"`
	Attempt            *controlmodel.ExecutionAttempt         `json:"attempt"`
	Profile            *controlmodel.RuntimeProfile           `json:"runtimeProfile,omitempty"`
	ExecutionOverrides *controlmodel.HostedExecutionOverrides `json:"executionOverrides,omitempty"`
	Definition         *provider.AgentDefinition              `json:"definition,omitempty"`
	AttemptToken       string                                 `json:"attemptToken"`
	TaskToken          string                                 `json:"taskToken"`
}

func (c *Client) httpClient() *http.Client {
	if c.HTTPClient != nil {
		return c.HTTPClient
	}
	return &http.Client{Timeout: 35 * time.Second}
}

func (c *Client) request(ctx context.Context, method, path string, body any, out any) (int, error) {
	return c.requestWithHeaders(ctx, method, path, body, out, nil)
}

func (c *Client) requestWithHeaders(ctx context.Context, method, path string, body any, out any, headers map[string]string) (int, error) {
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return 0, err
		}
		reader = bytes.NewReader(encoded)
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(c.BaseURL, "/")+path, reader)
	if err != nil {
		return 0, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.InternalToken != "" {
		if strings.HasPrefix(c.InternalToken, "asrh_") {
			req.Header.Set("Authorization", "Bearer "+c.InternalToken)
		} else {
			req.Header.Set("X-Builder-Internal-Token", c.InternalToken)
		}
	}
	for name, value := range headers {
		if value != "" && value != "<nil>" {
			req.Header.Set(name, value)
		}
	}
	resp, err := c.httpClient().Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNoContent {
		return resp.StatusCode, nil
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return resp.StatusCode, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return resp.StatusCode, fmt.Errorf("control plane %s %s: status=%d body=%s", method, path, resp.StatusCode, strings.TrimSpace(string(data)))
	}
	if out != nil && len(data) > 0 {
		if err := json.Unmarshal(data, out); err != nil {
			return resp.StatusCode, err
		}
	}
	return resp.StatusCode, nil
}

func (c *Client) Register(ctx context.Context, registration Registration) (*controlmodel.RuntimeHost, error) {
	var response struct {
		Host *controlmodel.RuntimeHost `json:"host"`
	}
	_, err := c.request(ctx, http.MethodPost, "/api/v1/runtime-hosts/register", registration, &response)
	return response.Host, err
}

func (c *Client) Heartbeat(ctx context.Context, host *controlmodel.RuntimeHost, active int32, capabilities json.RawMessage) (*controlmodel.RuntimeHost, error) {
	var response struct {
		Host *controlmodel.RuntimeHost `json:"host"`
	}
	_, err := c.request(ctx, http.MethodPost, "/api/v1/runtime-hosts/"+host.ID.String()+"/heartbeat", map[string]any{
		"generation": host.LeaseGeneration, "active": active, "capabilities": capabilities,
	}, &response)
	return response.Host, err
}

func (c *Client) Claim(ctx context.Context, host *controlmodel.RuntimeHost, owner, token string, ttl time.Duration) (*ClaimedWork, error) {
	var response ClaimedWork
	status, err := c.request(ctx, http.MethodPost, "/api/v1/runtime-hosts/"+host.ID.String()+"/execution-attempts/claim", map[string]any{
		"tenant": host.Tenant, "namespace": host.Namespace, "runtimePoolName": host.PoolName,
		"generation": host.LeaseGeneration, "leaseOwner": owner, "leaseToken": token,
		"leaseSeconds": int64(ttl.Seconds()),
	}, &response)
	if err != nil {
		return nil, err
	}
	if status == http.StatusNoContent {
		return nil, ErrNoWork
	}
	if response.Task == nil || response.Context == nil || response.Attempt == nil {
		return nil, fmt.Errorf("claim response missing task, context, or execution")
	}
	if response.AttemptToken == "" {
		return nil, fmt.Errorf("claim response missing execution attempt token")
	}
	if response.TaskToken == "" {
		return nil, fmt.Errorf("claim response missing agent task token")
	}
	c.attemptTokens.Store(response.Attempt.ID, response.AttemptToken)
	return &response, nil
}

func (c *Client) executionAction(ctx context.Context, hostID, executionID uuid.UUID, action string, payload map[string]any, out *controlmodel.ExecutionAttempt) error {
	var response struct {
		Attempt *controlmodel.ExecutionAttempt `json:"attempt"`
	}
	path := fmt.Sprintf("/api/v1/runtime-hosts/%s/execution-attempts/%s/%s", hostID, executionID, action)
	token, _ := c.attemptTokens.Load(executionID)
	_, err := c.requestWithHeaders(ctx, http.MethodPost, path, payload, &response,
		map[string]string{"X-Execution-Attempt-Token": fmt.Sprint(token)})
	if err == nil && response.Attempt != nil && out != nil {
		*out = *response.Attempt
	}
	if err == nil && response.Attempt != nil && controlmodel.IsExecutionAttemptTerminal(response.Attempt.State) {
		c.attemptTokens.Delete(executionID)
	}
	return err
}

func leasePayload(execution *controlmodel.ExecutionAttempt) map[string]any {
	return map[string]any{"leaseToken": execution.LeaseToken, "fencingToken": execution.FencingToken}
}

func (c *Client) Prepare(ctx context.Context, hostID uuid.UUID, execution *controlmodel.ExecutionAttempt) error {
	return c.executionAction(ctx, hostID, execution.ID, "preparing", leasePayload(execution), execution)
}

func (c *Client) Start(ctx context.Context, hostID uuid.UUID, execution *controlmodel.ExecutionAttempt, providerSessionID, workspaceKey string) error {
	payload := leasePayload(execution)
	payload["providerSessionId"] = providerSessionID
	payload["workspaceKey"] = workspaceKey
	return c.executionAction(ctx, hostID, execution.ID, "running", payload, execution)
}

func (c *Client) Renew(ctx context.Context, hostID uuid.UUID, execution *controlmodel.ExecutionAttempt, ttl time.Duration) error {
	payload := leasePayload(execution)
	payload["leaseSeconds"] = int64(ttl.Seconds())
	return c.executionAction(ctx, hostID, execution.ID, "renew", payload, execution)
}

func (c *Client) Checkpoint(ctx context.Context, hostID uuid.UUID, execution *controlmodel.ExecutionAttempt, providerSessionID string, checkpoint json.RawMessage) error {
	payload := leasePayload(execution)
	payload["providerSessionId"] = providerSessionID
	payload["checkpoint"] = checkpoint
	return c.executionAction(ctx, hostID, execution.ID, "checkpoint", payload, execution)
}

// PublishProviderEvent is intentionally optional on ControlPlaneClient so
// embedders and older test doubles remain source-compatible. Engine detects
// this capability and treats telemetry delivery as best effort.
func (c *Client) PublishProviderEvent(ctx context.Context, hostID uuid.UUID,
	execution *controlmodel.ExecutionAttempt, providerName string, ordinal int64, event provider.Event) error {
	payload := leasePayload(execution)
	payload["provider"] = providerName
	payload["ordinal"] = ordinal
	payload["eventType"] = event.Type
	payload["providerSessionId"] = event.ProviderSessionID
	payload["raw"] = event.Raw
	return c.executionAction(ctx, hostID, execution.ID, "events", payload, execution)
}

func (c *Client) Complete(ctx context.Context, hostID uuid.UUID, execution *controlmodel.ExecutionAttempt, result, checkpoint json.RawMessage) error {
	payload := leasePayload(execution)
	payload["result"] = result
	payload["checkpoint"] = checkpoint
	return c.executionAction(ctx, hostID, execution.ID, "complete", payload, execution)
}

func (c *Client) Fail(ctx context.Context, hostID uuid.UUID, execution *controlmodel.ExecutionAttempt, code, message string, checkpoint json.RawMessage) error {
	payload := leasePayload(execution)
	payload["failureCode"] = code
	payload["failureMessage"] = message
	payload["checkpoint"] = checkpoint
	return c.executionAction(ctx, hostID, execution.ID, "fail", payload, execution)
}

func (c *Client) Cancelled(ctx context.Context, hostID uuid.UUID, execution *controlmodel.ExecutionAttempt) error {
	return c.executionAction(ctx, hostID, execution.ID, "cancelled", leasePayload(execution), execution)
}

func (c *Client) AwaitToolApproval(ctx context.Context, taskID, taskToken string,
	request provider.ToolApprovalRequest) (provider.ToolApprovalDecision, error) {
	var created struct {
		Approval *controlmodel.Approval `json:"approval"`
	}
	path := "/api/v1/agent-tasks/" + taskID + "/runtime-approvals"
	_, err := c.approvalRequestWithRetry(ctx, http.MethodPost, path, map[string]any{
		"kind": "tool_confirmation", "toolUseId": request.ToolUseID,
		"toolName": request.ToolName, "inputPreview": request.Input,
		"inputSha256": request.InputSHA256, "expiresAt": request.ExpiresAt,
	}, &created, map[string]string{"X-Agent-Task-Token": taskToken})
	if err != nil || created.Approval == nil {
		if err == nil {
			err = fmt.Errorf("runtime approval response is missing approval")
		}
		return provider.ToolApprovalDecision{}, err
	}
	decisionPath := path + "/" + created.Approval.ID.String() + "/decision"
	for {
		var result struct {
			ApprovalID      string `json:"approvalId"`
			DecisionVersion int64  `json:"decisionVersion"`
			Status          string `json:"status"`
			Allow           bool   `json:"allow"`
			DenyMessage     string `json:"denyMessage"`
		}
		status, pollErr := c.approvalRequestWithRetry(ctx, http.MethodGet, decisionPath, nil, &result,
			map[string]string{"X-Agent-Task-Token": taskToken})
		if pollErr != nil {
			return provider.ToolApprovalDecision{}, pollErr
		}
		if status == http.StatusAccepted {
			select {
			case <-ctx.Done():
				return provider.ToolApprovalDecision{}, ctx.Err()
			case <-time.After(time.Second):
			}
			continue
		}
		if result.DecisionVersion <= 0 {
			return provider.ToolApprovalDecision{}, fmt.Errorf("runtime approval decision is missing a version")
		}
		ackPath := path + "/" + created.Approval.ID.String() + "/ack"
		if _, ackErr := c.approvalRequestWithRetry(ctx, http.MethodPost, ackPath,
			map[string]any{"decisionVersion": result.DecisionVersion}, nil,
			map[string]string{"X-Agent-Task-Token": taskToken}); ackErr != nil {
			return provider.ToolApprovalDecision{}, ackErr
		}
		return provider.ToolApprovalDecision{ApprovalID: result.ApprovalID,
			DecisionVersion: result.DecisionVersion, Allow: result.Allow,
			DenyMessage: result.DenyMessage}, nil
	}
}

// Approval create/ack are idempotent for the same tool-use and decision version.
// A brief control-plane outage must not discard an already approved tool call.
func (c *Client) approvalRequestWithRetry(ctx context.Context, method, path string, body, out any, headers map[string]string) (int, error) {
	retryCtx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	delay := 250 * time.Millisecond
	for {
		status, err := c.requestWithHeaders(retryCtx, method, path, body, out, headers)
		if err == nil {
			return status, nil
		}
		if retryCtx.Err() != nil {
			return status, retryCtx.Err()
		}
		var transportError *url.Error
		retryable := status == http.StatusRequestTimeout || status == http.StatusTooManyRequests || status >= 500 && status <= 599 || status == 0 && errors.As(err, &transportError) || errors.Is(err, io.ErrUnexpectedEOF) || errors.Is(err, io.EOF)
		if !retryable {
			return status, err
		}
		timer := time.NewTimer(delay)
		select {
		case <-retryCtx.Done():
			timer.Stop()
			return status, retryCtx.Err()
		case <-timer.C:
		}
		if delay < 2*time.Second {
			delay *= 2
		}
	}
}
