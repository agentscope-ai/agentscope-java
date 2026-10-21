// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
)

func exercisePortalHostCredentialRefresh(t *testing.T, server *Server, attempt *model.ExecutionAttempt, host *model.RuntimeHost, executable bool) string {
	t.Helper()
	hostToken, _, err := server.runtimeTokens.Mint(host.HostKey, host.Tenant, host.Namespace, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	expired, _ := server.taskTokens.MintAttempt(attempt.ID, attempt.DispatchGeneration, string(attempt.BackendKind), host.ID.String(), time.Now().Add(-48*time.Hour))
	path := "/api/internal/runtime/runtime-hosts/" + host.ID.String() + "/execution-attempts/" + attempt.ID.String() + "/credentials"
	request := func(credential, lease string, fence int64) *httptest.ResponseRecorder {
		body, _ := json.Marshal(map[string]any{"leaseToken": lease, "fencingToken": fence})
		req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(string(body)))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Builder-Internal-Token", "internal-fixture")
		req.Header.Set("X-Execution-Attempt-Token", expired)
		req.Header.Set("Authorization", "Bearer "+credential)
		out := httptest.NewRecorder()
		server.router.ServeHTTP(out, req)
		return out
	}
	if out := request("", attempt.LeaseToken, attempt.FencingToken); out.Code != http.StatusUnauthorized {
		t.Fatalf("expired attempt token alone renewed identity: %d %s", out.Code, out.Body.String())
	}
	if out := request(hostToken, "different-lease", attempt.FencingToken); out.Code != http.StatusConflict {
		t.Fatalf("different lease refreshed identity: %d %s", out.Code, out.Body.String())
	}
	if out := request(hostToken, attempt.LeaseToken, attempt.FencingToken+1); out.Code != http.StatusConflict {
		t.Fatalf("different fence refreshed identity: %d %s", out.Code, out.Body.String())
	}
	out := request(hostToken, attempt.LeaseToken, attempt.FencingToken)
	if out.Code != http.StatusOK {
		t.Fatalf("owned identity refresh: %d %s", out.Code, out.Body.String())
	}
	var value struct {
		Attempt      *model.ExecutionAttempt `json:"attempt"`
		AttemptToken string                  `json:"attemptToken"`
		TaskToken    string                  `json:"taskToken"`
	}
	if err := json.Unmarshal(out.Body.Bytes(), &value); err != nil {
		t.Fatal(err)
	}
	if (value.TaskToken != "") != executable {
		t.Fatalf("task credential did not respect grant: executable=%t token present=%t", executable, value.TaskToken != "")
	}
	if value.Attempt.LeaseExpiresAt == nil || value.Attempt.LeaseExpiresAt.After(time.Now().Add(65*time.Second)) {
		t.Fatal("credential refresh extended the physical lease")
	}
	claims, err := server.taskTokens.VerifyAttemptClaims(value.AttemptToken, time.Now())
	if err != nil || claims.ExpiresAt.After(time.Now().Add(16*time.Minute)) {
		t.Fatalf("reporting token expiry=%+v %v", claims, err)
	}
	if executable {
		scoped, err := server.taskTokens.VerifyClaims(value.TaskToken, time.Now())
		if err != nil || scoped.Fence != attempt.FencingToken || scoped.HostID != host.ID || scoped.AttemptID != attempt.ID {
			t.Fatalf("task physical identity=%+v %v", scoped, err)
		}
	}
	contextRequest := func(taskID string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, "/api/internal/runtime/agent-tasks/"+taskID+"/context", nil)
		req.Header.Set("X-Builder-Internal-Token", "internal-fixture")
		req.Header.Set("X-Agent-Task-Token", value.TaskToken)
		out := httptest.NewRecorder()
		server.router.ServeHTTP(out, req)
		return out
	}
	if executable {
		context := contextRequest(attempt.AgentTaskID.String())
		if context.Code != http.StatusOK || strings.Contains(context.Body.String(), `"taskToken":"`) {
			t.Fatalf("fenced CLI context=%d %s", context.Code, context.Body.String())
		}
		if context := contextRequest(host.ID.String()); context.Code != http.StatusForbidden {
			t.Fatalf("CLI context crossed task scope=%d %s", context.Code, context.Body.String())
		}
	} else if context := contextRequest(attempt.AgentTaskID.String()); context.Code != http.StatusUnauthorized {
		t.Fatalf("revoked CLI acquired task context=%d %s", context.Code, context.Body.String())
	}
	return value.TaskToken
}
