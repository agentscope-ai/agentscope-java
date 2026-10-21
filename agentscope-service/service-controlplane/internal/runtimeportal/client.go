// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeportal

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
)

// Client uses separate bounded HTTP pools for execution authorization and fact
// delivery. A full event backlog cannot consume authorization renewal capacity.
type Client struct {
	baseURL       string
	internalToken string
	control       *http.Client
	facts         *http.Client
}

type ResponseError struct{ Status int }

func (e *ResponseError) Error() string { return fmt.Sprintf("Portal returned HTTP %d", e.Status) }

func NewClient(baseURL, token string) (*Client, error) {
	parsed, err := url.Parse(baseURL)
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return nil, fmt.Errorf("invalid Portal service URL")
	}
	if strings.TrimSpace(token) == "" {
		return nil, fmt.Errorf("Portal service token is required")
	}
	newPool := func(slots int) *http.Client {
		transport := http.DefaultTransport.(*http.Transport).Clone()
		transport.MaxConnsPerHost = slots
		transport.MaxIdleConnsPerHost = slots
		transport.MaxIdleConns = slots
		return &http.Client{Transport: transport, Timeout: 5 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), internalToken: token, control: newPool(16), facts: newPool(8)}, nil
}

func (c *Client) Close() { c.control.CloseIdleConnections(); c.facts.CloseIdleConnections() }

// ManagedAttempt asks the resource control plane to admit/cancel one durable
// Managed Attempt. It cannot supply arbitrary input, caller identity or configuration.
func (c *Client) ManagedAttempt(ctx context.Context, id, operation string) (string, error) {
	if operation != "admit" && operation != "cancel" && operation != "observe" && operation != "authorize" {
		return "", fmt.Errorf("invalid Managed Attempt operation")
	}
	var result runtimeprotocol.ManagedAttemptReceipt
	err := c.call(ctx, c.control, "/api/internal/managed-attempts/"+url.PathEscape(id)+"/"+operation, struct{}{}, &result)
	return result.TurnID, err
}

// ManagedAttemptSettled reads the durable native Turn barrier through the
// resource control plane; runtime replicas never synthesize this from Task state.
func (c *Client) ManagedAttemptSettled(ctx context.Context, id string) (bool, error) {
	var result runtimeprotocol.ManagedAttemptReceipt
	err := c.call(ctx, c.control, "/api/internal/managed-attempts/"+url.PathEscape(id)+"/settled", struct{}{}, &result)
	if err != nil {
		return false, err
	}
	if result.Settled == nil {
		return false, fmt.Errorf("Managed execution barrier is missing")
	}
	return *result.Settled, nil
}

func (c *Client) call(ctx context.Context, client *http.Client, path string, input, out any) error {
	raw, err := json.Marshal(input)
	if err != nil {
		return err
	}
	if len(raw) > runtimeprotocol.MaxMessageBytes {
		return fmt.Errorf("runtime message exceeds protocol limit")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Builder-Internal-Token", c.internalToken)
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return &ResponseError{Status: response.StatusCode}
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, runtimeprotocol.MaxMessageBytes+1))
	if err != nil {
		return err
	}
	if len(body) > runtimeprotocol.MaxMessageBytes {
		return fmt.Errorf("Portal response exceeds protocol limit")
	}
	return runtimeprotocol.Decode(bytes.NewReader(body), out)
}

func (c *Client) Grant(ctx context.Context, turn Turn, renewal bool) (runtimeprotocol.RuntimeExecutionGrant, error) {
	command := turn.Command
	activation := turn.ActivationCommandID
	if activation == "" {
		activation = command.Context.CommandID
	}
	request := runtimeprotocol.RuntimeExecutionRequest{ActivationCommandID: activation, Context: command.Context,
		InputDigest: command.InputDigest, AdmissionSequence: command.AdmissionSequence, ExecutionGeneration: turn.Generation, ExecutionFence: turn.Fence, Renewal: renewal}
	var grant runtimeprotocol.RuntimeExecutionGrant
	if err := c.call(ctx, c.control, "/api/internal/runtime/execution-grants", request, &grant); err != nil {
		return grant, err
	}
	if !SameScope(grant.Context, command.Context) || grant.Context.CommandID != command.Context.CommandID ||
		grant.Context.TurnID == nil || command.Context.TurnID == nil || *grant.Context.TurnID != *command.Context.TurnID ||
		grant.ExecutionGeneration != turn.Generation || grant.ExecutionFence != turn.Fence || grant.ValidUntil <= time.Now().UnixMilli() {
		return runtimeprotocol.RuntimeExecutionGrant{}, ErrPermission
	}
	actor, _ := runtimeprotocol.Digest(command.Context.Actor)
	received, _ := runtimeprotocol.Digest(grant.Context.Actor)
	if actor != received {
		return runtimeprotocol.RuntimeExecutionGrant{}, ErrPermission
	}
	return grant, nil
}

func (c *Client) DeliverFact(ctx context.Context, fact runtimeprotocol.RuntimeFact) error {
	var receipt runtimeprotocol.RuntimeFactReceipt
	if err := c.call(ctx, c.facts, "/api/internal/runtime/facts", fact, &receipt); err != nil {
		return err
	}
	if receipt.SessionID != fact.Context.SessionID || receipt.Source != fact.Source || receipt.EventID != fact.EventID || receipt.SourceSequence != fact.SourceSequence {
		return ErrConflict
	}
	return nil
}

func (c *Client) SubmitAgentSignal(ctx context.Context, request runtimeprotocol.RuntimeAgentSignal) (map[string]any, error) {
	var receipt runtimeprotocol.RuntimeAgentSignalReceipt
	if err := c.call(ctx, c.control, "/api/internal/runtime/agent-signals", request, &receipt); err != nil {
		return nil, err
	}
	if receipt.CommandID == "" || receipt.SessionID != request.SessionID || receipt.TurnID != request.TurnID || receipt.Kind != "signal" ||
		(receipt.Status != "queued" && receipt.Status != "runtime_accepted") || receipt.CommandStatus == "" {
		return nil, ErrConflict
	}
	return map[string]any{"command_id": receipt.CommandID, "session_id": receipt.SessionID, "turn_id": receipt.TurnID,
		"kind": receipt.Kind, "status": receipt.Status, "command_status": receipt.CommandStatus, "created_at": receipt.CreatedAt}, nil
}
