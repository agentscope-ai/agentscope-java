// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimehost

import (
	"context"
	"encoding/base64"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
)

func (c *Client) storeAttemptToken(id uuid.UUID, token string) {
	c.attemptTokens.Store(id, token)
	// This is a scheduling hint, never an authorization decision. The server
	// validates the signature, current Host, generation and physical lease.
	payload, err := base64.RawURLEncoding.DecodeString(strings.Split(token, ".")[0])
	if err != nil {
		return
	}
	fields := strings.Split(string(payload), ".")
	if len(fields) != 6 || fields[0] != "attempt" {
		return
	}
	expires, err := strconv.ParseInt(fields[len(fields)-1], 10, 64)
	if err == nil {
		c.refreshAfter.Store(id, time.Unix(expires, 0).Add(-2*time.Minute))
	}
}

func (c *Client) forgetCredentials(id uuid.UUID) {
	c.attemptTokens.Delete(id)
	c.taskTokens.Delete(id)
	c.refreshAfter.Delete(id)
}

func (c *Client) CurrentTaskToken(id uuid.UUID) string {
	value, _ := c.taskTokens.Load(id)
	token, _ := value.(string)
	return token
}

// RefreshCredentials uses the Host credential and the exact persisted lease.
// It works after token expiry, including journal replay, without renewing work.
func (c *Client) RefreshCredentials(ctx context.Context, hostID uuid.UUID, attempt *model.ExecutionAttempt) error {
	var response struct {
		Attempt      *model.ExecutionAttempt `json:"attempt"`
		AttemptToken string                  `json:"attemptToken"`
		TaskToken    string                  `json:"taskToken"`
	}
	path := fmt.Sprintf("/api/v1/runtime-hosts/%s/execution-attempts/%s/credentials", hostID, attempt.ID)
	_, err := c.request(ctx, http.MethodPost, path, leasePayload(attempt), &response)
	if err != nil {
		return err
	}
	if response.Attempt == nil || response.Attempt.ID != attempt.ID ||
		response.Attempt.FencingToken != attempt.FencingToken || response.Attempt.LeaseToken != attempt.LeaseToken ||
		response.Attempt.DispatchGeneration != attempt.DispatchGeneration || response.AttemptToken == "" {
		return fmt.Errorf("credential refresh returned a different execution lease")
	}
	c.storeAttemptToken(attempt.ID, response.AttemptToken)
	c.taskTokens.Store(attempt.ID, response.TaskToken)
	*attempt = *response.Attempt
	return nil
}
