/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package product

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
)

// ManagedServiceError retains the upstream HTTP status for authenticated native runtime calls.
type ManagedServiceError struct {
	StatusCode int
	Message    string
}

func (e *ManagedServiceError) Error() string {
	return fmt.Sprintf("managed service API HTTP %d: %s", e.StatusCode, e.Message)
}

// ManagedRuntimeCall carries authenticated origin context, not a replacement owner identity.
type ManagedRuntimeCall struct {
	Context          runtimeprotocol.Context `json:"context"`
	Operation        string                  `json:"operation"`
	Payload          any                     `json:"payload,omitempty"`
	After            string                  `json:"after,omitempty"`
	Through          string                  `json:"through,omitempty"`
	Order            string                  `json:"order,omitempty"`
	Status           string                  `json:"status,omitempty"`
	Limit            int                     `json:"limit,omitempty"`
	InputID          string                  `json:"inputId,omitempty"`
	SnapshotID       string                  `json:"snapshotId,omitempty"`
	ResourceKind     string                  `json:"resourceKind,omitempty"`
	Desired          string                  `json:"desired,omitempty"`
	ReceiptID        string                  `json:"receiptId,omitempty"`
	ResourceID       string                  `json:"resourceId,omitempty"`
	ExpectedRevision string                  `json:"expectedRevision,omitempty"`
	Offset           int                     `json:"offset,omitempty"`
}

func (s *Server) ManagedRuntimeRequest(ctx context.Context, owner string, call ManagedRuntimeCall) (json.RawMessage, error) {
	if err := validateManagedOrigin(owner, call.Context); err != nil {
		return nil, err
	}
	if strings.TrimSpace(call.Operation) == "" {
		return nil, fmt.Errorf("Managed runtime operation is required")
	}
	endpoint := "/api/internal/managed/sessions/" + url.PathEscape(call.Context.SessionID) + "/request"
	return s.managedRuntimePost(ctx, owner, endpoint, call)
}

// ManagedRuntimeProvision stores a fixed definition in Portal; no Brain bootstrap is involved.
func (s *Server) ManagedRuntimeProvision(ctx context.Context, owner string, admission runtimeprotocol.RuntimeSessionAdmission) (json.RawMessage, error) {
	if err := validateManagedOrigin(owner, admission.Bootstrap.Context); err != nil {
		return nil, err
	}
	if admission.Bootstrap.RuntimeKind != "managed" {
		return nil, fmt.Errorf("invalid Managed resource admission")
	}
	return s.managedRuntimePost(ctx, owner, "/api/internal/runtime/sessions/provision", admission)
}

func validateManagedOrigin(owner string, origin runtimeprotocol.Context) error {
	if err := origin.Validate(); err != nil {
		return err
	}
	if strings.TrimSpace(owner) == "" || origin.PermissionExpiresAt <= time.Now().UnixMilli() {
		return fmt.Errorf("Managed runtime owner and valid context are required")
	}
	if internal, ok := origin.Actor["internal"].(bool); !ok || internal {
		return fmt.Errorf("Managed runtime must retain the original Actor")
	}
	if user, ok := origin.Actor["user"].(string); !ok || user == "" {
		return fmt.Errorf("Managed runtime Actor is required")
	}
	if _, ok := origin.Actor["key_hash"].(string); !ok {
		return fmt.Errorf("Managed runtime Actor key hash is required")
	}
	return nil
}

// ManagedRuntimeUpload sends raw bytes so large files do not inflate a JSON request or MQ message.
func (s *Server) ManagedRuntimeUpload(ctx context.Context, owner string, origin runtimeprotocol.Context, name, media string, body io.Reader) (json.RawMessage, error) {
	encoded, err := managedRuntimeContextHeader(owner, origin)
	if err != nil {
		return nil, err
	}
	if body == nil || name == "" {
		return nil, fmt.Errorf("Managed file name and body are required")
	}
	request, err := s.managedRuntimeHttpRequest(ctx, owner, "/api/internal/managed/sessions/"+url.PathEscape(origin.SessionID)+"/files", io.LimitReader(body, (16<<20)+1))
	if err != nil {
		return nil, err
	}
	// A rejected streaming body may leave unread HTTP/1 bytes; never pool this connection.
	request.Close = true
	request.Header.Set("X-Managed-Runtime-Context", encoded)
	request.Header.Set("X-File-Name", url.QueryEscape(name))
	if media == "" {
		media = "application/octet-stream"
	}
	request.Header.Set("Content-Type", media)
	return s.managedRuntimeResponse(request, 30*time.Second)
}

func managedRuntimeContextHeader(owner string, origin runtimeprotocol.Context) (string, error) {
	if err := validateManagedOrigin(owner, origin); err != nil {
		return "", err
	}
	raw, err := json.Marshal(origin)
	if err != nil {
		return "", err
	}
	encoded := base64.RawURLEncoding.EncodeToString(raw)
	if len(encoded) > 4096 {
		return "", fmt.Errorf("Managed file runtime context exceeds its header limit")
	}
	return encoded, nil
}

// ManagedRuntimeFileContent reads a fixed, scope-bound file route; callers must close its body.
func (s *Server) ManagedRuntimeFileContent(ctx context.Context, owner string, origin runtimeprotocol.Context, file string) (*http.Response, error) {
	encoded, err := managedRuntimeContextHeader(owner, origin)
	if err != nil {
		return nil, err
	}
	if file == "" {
		return nil, fmt.Errorf("Managed file ID is required")
	}
	request, err := s.managedRuntimeHttpRequest(ctx, owner, "/api/internal/managed/sessions/"+url.PathEscape(origin.SessionID)+"/files/"+url.PathEscape(file)+"/content", nil)
	if err != nil {
		return nil, err
	}
	request.Method = http.MethodGet
	request.Header.Set("X-Managed-Runtime-Context", encoded)
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		defer response.Body.Close()
		raw, readErr := io.ReadAll(io.LimitReader(response.Body, (1<<20)+1))
		if readErr != nil {
			return nil, readErr
		}
		if len(raw) > 1<<20 {
			return nil, fmt.Errorf("Managed file error exceeds its byte limit")
		}
		return nil, &ManagedServiceError{StatusCode: response.StatusCode, Message: string(raw)}
	}
	if response.ContentLength < 0 || response.ContentLength > 16<<20 {
		response.Body.Close()
		return nil, fmt.Errorf("Managed file response requires a size within 16 MiB")
	}
	return response, nil
}

func (s *Server) managedRuntimePost(ctx context.Context, owner, path string, body any) (json.RawMessage, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	if len(raw) > 16<<20 {
		return nil, fmt.Errorf("Managed runtime request exceeds 16 MiB")
	}
	req, err := s.managedRuntimeHttpRequest(ctx, owner, path, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	return s.managedRuntimeResponse(req, 15*time.Second)
}

func (s *Server) managedRuntimeHttpRequest(ctx context.Context, owner, path string, body io.Reader) (*http.Request, error) {
	if strings.TrimSpace(s.cfg.SessionPortalURL) == "" {
		return nil, fmt.Errorf("Managed Session Portal URL is not configured")
	}
	endpoint := strings.TrimRight(s.cfg.SessionPortalURL, "/") + path
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	req.Header.Set("X-Builder-Internal-User", owner)
	return req, nil
}

func (s *Server) managedRuntimeResponse(req *http.Request, timeout time.Duration) (json.RawMessage, error) {
	client := &http.Client{Timeout: timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	maximum := int64(16 << 20)
	if response.StatusCode >= 300 {
		maximum = 1 << 20
	}
	payload, err := io.ReadAll(io.LimitReader(response.Body, maximum+1))
	if err != nil {
		return nil, err
	}
	if int64(len(payload)) > maximum {
		return nil, fmt.Errorf("Managed runtime response exceeds its byte limit")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, &ManagedServiceError{StatusCode: response.StatusCode, Message: string(payload)}
	}
	if !json.Valid(payload) {
		return nil, fmt.Errorf("Managed runtime returned invalid JSON")
	}
	return payload, nil
}
