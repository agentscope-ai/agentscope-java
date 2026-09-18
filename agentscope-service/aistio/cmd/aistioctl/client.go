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

package main

import (
	"bytes"
	"fmt"
	"net/http"
	"os"
	"strings"
)

func newAPIClient() *http.Client {
	return &http.Client{
		Transport: &tokenTransport{
			token: apiToken,
			base:  http.DefaultTransport,
		},
	}
}

func agentTaskToken() string {
	for _, key := range []string{"AGENTSCOPE_TASK_TOKEN", "AISTIO_AGENT_TASK_TOKEN"} {
		if value := strings.TrimSpace(os.Getenv(key)); value != "" {
			return value
		}
	}
	return ""
}

func doTaskAPI(method, path string, body []byte, token string) (*http.Response, error) {
	if token == "" {
		return nil, fmt.Errorf("task-scoped authentication is required; run inside a hosted AgentTask or set AGENTSCOPE_TASK_TOKEN")
	}
	req, err := http.NewRequest(method, strings.TrimRight(apiEndpoint, "/")+path, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	if len(body) > 0 {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("X-Agent-Task-Token", token)
	return http.DefaultClient.Do(req)
}

func doAgentOrHumanAPI(method, path string, body []byte) (*http.Response, error) {
	if token := agentTaskToken(); token != "" {
		return doTaskAPI(method, path, body, token)
	}
	return doAPI(method, path, body)
}

type tokenTransport struct {
	token string
	base  http.RoundTripper
}

func (t *tokenTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if t.token != "" {
		req.Header.Set("Authorization", "Bearer "+t.token)
	}
	return t.base.RoundTrip(req)
}
