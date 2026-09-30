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

package worksource

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

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type githubConfiguration struct {
	APIBase    string `json:"apiBase,omitempty"`
	Owner      string `json:"owner"`
	Repository string `json:"repository"`
	Token      string `json:"token"`
}

// GitHubHTTPTransport invokes GitHub or GitHub Enterprise through its REST API.
type GitHubHTTPTransport struct{ Client *http.Client }

func (t *GitHubHTTPTransport) configuration(source *controlmodel.WorkSource) (githubConfiguration, error) {
	var cfg githubConfiguration
	if err := json.Unmarshal(source.Configuration, &cfg); err != nil {
		return cfg, fmt.Errorf("decode GitHub Work Source configuration: %w", err)
	}
	if cfg.APIBase == "" {
		cfg.APIBase = "https://api.github.com"
	}
	if cfg.Owner == "" || cfg.Repository == "" || cfg.Token == "" {
		return cfg, fmt.Errorf("GitHub owner, repository and token are required")
	}
	return cfg, nil
}

func (t *GitHubHTTPTransport) request(ctx context.Context, source *controlmodel.WorkSource, method, path string, body any, out any) error {
	cfg, err := t.configuration(source)
	if err != nil {
		return err
	}
	var reader io.Reader
	if body != nil {
		payload, marshalErr := json.Marshal(body)
		if marshalErr != nil {
			return marshalErr
		}
		reader = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(cfg.APIBase, "/")+path, reader)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("Authorization", "Bearer "+cfg.Token)
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	client := t.Client
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	response, err := client.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 2<<20))
	if err != nil {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("GitHub API %s %s returned HTTP %d: %s", method, path, response.StatusCode, strings.TrimSpace(string(data)))
	}
	if out != nil && len(data) > 0 {
		if err := json.Unmarshal(data, out); err != nil {
			return fmt.Errorf("decode GitHub API response: %w", err)
		}
	}
	return nil
}

func githubIssuePath(cfg githubConfiguration, number string) string {
	return "/repos/" + url.PathEscape(cfg.Owner) + "/" + url.PathEscape(cfg.Repository) + "/issues/" + url.PathEscape(number)
}

func (t *GitHubHTTPTransport) FetchIssue(ctx context.Context, source *controlmodel.WorkSource, id string) (*controlmodel.IssueExternalRef, error) {
	cfg, err := t.configuration(source)
	if err != nil {
		return nil, err
	}
	var result githubIssue
	if err := t.request(ctx, source, http.MethodGet, githubIssuePath(cfg, id), nil, &result); err != nil {
		return nil, err
	}
	projection, _ := json.Marshal(result)
	return &controlmodel.IssueExternalRef{WorkSourceID: source.ID, ExternalID: fmt.Sprint(result.ID), ExternalNumber: fmt.Sprint(result.Number), ExternalURL: result.HTMLURL, ExternalVersion: result.UpdatedAt, Projection: projection}, nil
}

func (t *GitHubHTTPTransport) ApplyIssueCommand(ctx context.Context, source *controlmodel.WorkSource, ref *controlmodel.IssueExternalRef, command IssueCommand) error {
	cfg, err := t.configuration(source)
	if err != nil {
		return err
	}
	if ref == nil || ref.ExternalNumber == "" {
		return fmt.Errorf("GitHub Issue external number is unavailable")
	}
	var payload map[string]any
	if len(command.Payload) > 0 {
		if err := json.Unmarshal(command.Payload, &payload); err != nil {
			return fmt.Errorf("decode Issue command: %w", err)
		}
	}
	if payload == nil {
		payload = map[string]any{}
	}
	if status, ok := payload["status"].(string); ok {
		if status == string(controlmodel.IssueDone) || status == string(controlmodel.IssueCancelled) {
			payload["state"] = "closed"
		} else {
			payload["state"] = "open"
		}
		delete(payload, "status")
	}
	delete(payload, "expectedVersion")
	delete(payload, "reason")
	if len(payload) == 0 {
		return fmt.Errorf("GitHub Issue command %q has no applicable fields", command.Action)
	}
	return t.request(ctx, source, http.MethodPatch, githubIssuePath(cfg, ref.ExternalNumber), payload, nil)
}

func (t *GitHubHTTPTransport) PublishComment(ctx context.Context, source *controlmodel.WorkSource, ref *controlmodel.IssueExternalRef, comment *controlmodel.Comment) (*PublishedComment, error) {
	cfg, err := t.configuration(source)
	if err != nil {
		return nil, err
	}
	if ref == nil || ref.ExternalNumber == "" {
		return nil, fmt.Errorf("GitHub Issue external number is unavailable")
	}
	var result struct {
		ID int64 `json:"id"`
	}
	path := githubIssuePath(cfg, ref.ExternalNumber) + "/comments"
	if err := t.request(ctx, source, http.MethodPost, path, map[string]string{"body": comment.Content}, &result); err != nil {
		return nil, err
	}
	return &PublishedComment{ExternalID: fmt.Sprint(result.ID)}, nil
}

func (t *GitHubHTTPTransport) Reconcile(context.Context, *controlmodel.WorkSource) error {
	return nil
}
