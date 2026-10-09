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

package httpapi

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type publicSessionFile struct {
	ID          uuid.UUID `json:"id"`
	SessionID   uuid.UUID `json:"session_id"`
	Name        string    `json:"name"`
	ContentType string    `json:"content_type"`
	Size        int64     `json:"size"`
	Checksum    string    `json:"checksum"`
	CreatedAt   time.Time `json:"created_at"`
	StorageKey  string    `json:"storage_key,omitempty"`
	NativeID    string    `json:"native_id,omitempty"`
}

func sessionFilePath(id uuid.UUID) string           { return "service-session-files/" + id.String() }
func (f publicSessionFile) view() publicSessionFile { f.StorageKey = ""; f.NativeID = ""; return f }
func (s *Server) getPublicSessionFile(ctx context.Context, v *sessionapi.Session, id string) (*publicSessionFile, error) {
	r, err := s.store.KV().Get(ctx, v.Tenant, sessionFilePath(v.ID), id)
	if err != nil {
		return nil, err
	}
	var f publicSessionFile
	err = json.Unmarshal(r.Value, &f)
	return &f, err
}
func (s *Server) publicSessionFileUpload(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "interact")
	if !ok {
		return
	}
	if v.Status != "active" {
		c.JSON(409, ErrorResponse{Error: "Session is not active"})
		return
	}
	if s.artifactProvider == nil {
		c.JSON(503, ErrorResponse{Error: "file storage is unavailable"})
		return
	}
	key := strings.TrimSpace(c.GetHeader("Idempotency-Key"))
	if key == "" || len(key) > 256 {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key must contain 1..256 characters"})
		return
	}
	name, err := url.QueryUnescape(c.GetHeader("X-File-Name"))
	if err != nil || strings.TrimSpace(name) == "" || len(name) > 1024 {
		c.JSON(400, ErrorResponse{Error: "X-File-Name must contain a URL-encoded filename"})
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 16<<20))
	if err != nil {
		c.JSON(413, ErrorResponse{Error: "file exceeds 16 MiB"})
		return
	}
	sum := sha256.Sum256(body)
	checksum := hex.EncodeToString(sum[:])
	id := uuid.NewSHA1(v.ID, []byte("file:"+key))
	contentType := c.GetHeader("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	err = s.store.WithSessionLock(c, "session-file:"+id.String(), func(ctx context.Context) error {
		f, e := s.getPublicSessionFile(ctx, v, id.String())
		if e == nil {
			if f.Checksum != checksum || f.Name != name || f.ContentType != contentType {
				return store.ErrConflict
			}
			c.JSON(200, f.view())
			return nil
		}
		if !errors.Is(e, store.ErrNotFound) {
			return e
		}
		f = &publicSessionFile{ID: id, SessionID: v.ID, Name: name, ContentType: contentType, Size: int64(len(body)), Checksum: checksum, CreatedAt: time.Now().UTC(), StorageKey: "session-inputs/" + v.ID.String() + "/" + id.String()}
		if _, e = s.artifactProvider.Put(ctx, f.StorageKey, bytes.NewReader(body)); e != nil {
			return e
		}
		raw, _ := json.Marshal(f)
		if _, e = s.store.KV().Put(ctx, v.Tenant, sessionFilePath(v.ID), id.String(), raw); e != nil {
			return e
		}
		c.JSON(201, f.view())
		return nil
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
	}
}
func (s *Server) publicSessionFiles(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	offset := max(0, queryInt(c, "offset", 0))
	limit := queryInt(c, "limit", 100)
	if limit < 1 || limit > 200 {
		limit = 100
	}
	rows, err := s.store.KV().Search(c, v.Tenant, sessionFilePath(v.ID), limit+1, offset)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var next any
	if len(rows) > limit {
		next = offset + limit
		rows = rows[:limit]
	}
	items := []publicSessionFile{}
	for _, r := range rows {
		var f publicSessionFile
		if err = json.Unmarshal(r.Value, &f); err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		items = append(items, f.view())
	}
	c.JSON(200, gin.H{"items": items, "next_offset": next})
}
func (s *Server) publicSessionFileContent(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	f, err := s.getPublicSessionFile(c, v, c.Param("fileId"))
	if errors.Is(err, store.ErrNotFound) {
		// Files created by Managed tools live in its runtime file store. The
		// runtime verifies their Session ownership after public authorization.
		contract, e := serviceapi.ReadContract(v.Contract)
		if e == nil && s.publicSessionIsManaged(contract) {
			s.publicSessionNativeResource(c)
			return
		}
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.artifactProvider == nil {
		c.Status(503)
		return
	}
	reader, _, err := s.artifactProvider.Open(c, f.StorageKey)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	defer reader.Close()
	c.DataFromReader(200, f.Size, f.ContentType, reader, map[string]string{"Content-Disposition": "attachment; filename*=UTF-8''" + url.PathEscape(f.Name), "X-Content-Type-Options": "nosniff"})
}

func (s *Server) publicNativeSession(ctx context.Context, v *sessionapi.Session, contract *serviceapi.Contract) (string, string, error) {
	conv, err := s.ensurePublicConversation(ctx, v, contract)
	if err != nil {
		return "", "", err
	}
	binding, err := s.store.AgentCatalog().GetBinding(ctx, conv.BindingID)
	if err != nil {
		return "", "", err
	}
	var cfg model.ManagedBindingConfiguration
	if binding.Kind != model.DataPlaneManaged || json.Unmarshal(binding.Configuration, &cfg) != nil || cfg.OwnerRef == "" {
		return "", "", fmt.Errorf("session is not bound to a Managed Agent")
	}
	return cfg.OwnerRef, conv.SessionID, nil
}

func (s *Server) publicSessionNativeResource(c *gin.Context) {
	scope := "read"
	if c.Request.Method != "GET" {
		scope = "interact"
	}
	v, ok := s.loadPublicSession(c, scope)
	if !ok {
		return
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	suffix := strings.TrimPrefix(c.Request.URL.Path, "/api/v1/agent-sessions/"+v.ID.String())
	if !s.publicSessionIsManaged(contract) {
		if c.Request.Method == "GET" {
			field := strings.TrimPrefix(suffix, "/")
			if field == "artifacts" || field == "usage" || field == "items" || field == "tools" || field == "required-actions" {
				if err = s.projectPublicSession(c, v); err != nil {
					s.writeControlPlaneError(c, err)
					return
				}
				snapshot, e := s.sessionJournal(v).Snapshot(c)
				if e != nil {
					s.writeControlPlaneError(c, e)
					return
				}
				if field == "required-actions" {
					field = "required_actions"
				}
				c.JSON(200, gin.H{"data": sessionSnapshotView(v, snapshot)[field], "as_of": snapshot.AsOf})
				return
			}
		}
		sessionUnsupported(c, suffix)
		return
	}
	if scope != "read" && v.Status != "active" {
		c.JSON(409, ErrorResponse{Error: "Session is not active"})
		return
	}
	owner, native, err := s.publicNativeSession(c, v, contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	// The route table admits only known resource paths. Do not forward user-selected URLs.
	if c.Request.URL.RawQuery != "" {
		suffix += "?" + c.Request.URL.RawQuery
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 16<<20)
	var body io.Reader = c.Request.Body
	if strings.HasPrefix(suffix, "/inputs/inject") || c.Request.Method == "POST" && suffix == "/artifacts" {
		raw, e := io.ReadAll(c.Request.Body)
		if e != nil {
			c.Status(413)
			return
		}
		input, e := s.nativePublicSessionInput(c, &model.EndpointInvocation{Contract: v.Contract, Input: raw, SessionID: native}, owner)
		if e != nil {
			c.JSON(400, ErrorResponse{Error: e.Error()})
			return
		}
		raw, _ = json.Marshal(input)
		body = bytes.NewReader(raw)
	}
	response, err := s.product.ManagedSessionResource(c, owner, native, c.Request.Method, suffix, c.Request.Header, body)
	if err != nil {
		c.JSON(502, ErrorResponse{Error: err.Error()})
		return
	}
	defer response.Body.Close()
	for _, h := range []string{"Content-Type", "Content-Disposition", "ETag"} {
		if value := response.Header.Get(h); value != "" {
			c.Header(h, value)
		}
	}
	if strings.Contains(response.Header.Get("Content-Type"), "application/json") {
		var result any
		if err = json.NewDecoder(io.LimitReader(response.Body, 32<<20)).Decode(&result); err != nil {
			c.JSON(502, ErrorResponse{Error: "invalid runtime response"})
			return
		}
		mapping, e := s.nativeSessionMapping(c, v, native)
		if e != nil {
			s.writeControlPlaneError(c, e)
			return
		}
		c.JSON(response.StatusCode, translateNativeValue(result, mapping))
		return
	}
	c.Status(response.StatusCode)
	contentType := response.Header.Get("Content-Type")
	if strings.Contains(contentType, "text/event-stream") || strings.Contains(contentType, "ndjson") || strings.Contains(contentType, "jsonl") {
		mapping, e := s.nativeSessionMapping(c, v, native)
		if e != nil {
			return
		}
		scanner := bufio.NewScanner(response.Body)
		scanner.Buffer(make([]byte, 64<<10), 16<<20)
		for scanner.Scan() {
			line := scanner.Text()
			prefix, payload := "", line
			if strings.HasPrefix(line, "data:") {
				prefix, payload = "data: ", strings.TrimSpace(strings.TrimPrefix(line, "data:"))
			}
			var value any
			if json.Unmarshal([]byte(payload), &value) == nil {
				raw, _ := json.Marshal(translateNativeValue(value, mapping))
				line = prefix + string(raw)
			}
			if _, e = fmt.Fprintln(c.Writer, line); e != nil {
				return
			}
			c.Writer.Flush()
		}
		return
	}
	_, _ = io.Copy(c.Writer, response.Body)
}

func sessionInputFiles(input map[string]json.RawMessage) ([]string, error) {
	var payload any
	raw, _ := json.Marshal(input)
	if err := json.Unmarshal(raw, &payload); err != nil {
		return nil, err
	}
	files := []string{}
	seen := map[string]bool{}
	var visit func(any) error
	visit = func(v any) error {
		switch x := v.(type) {
		case map[string]any:
			if id, exists := x["file_id"]; exists {
				str, ok := id.(string)
				if !ok || str == "" {
					return fmt.Errorf("file_id must be a Session file ID")
				}
				if !seen[str] {
					files = append(files, str)
					seen[str] = true
				}
			}
			for _, value := range x {
				if err := visit(value); err != nil {
					return err
				}
			}
		case []any:
			for _, value := range x {
				if err := visit(value); err != nil {
					return err
				}
			}
		}
		return nil
	}
	return files, visit(payload)
}
func (s *Server) validatePublicSessionInput(ctx context.Context, v *sessionapi.Session, contract *serviceapi.Contract, input map[string]json.RawMessage) error {
	ids, err := sessionInputFiles(input)
	if err != nil {
		return err
	}
	for _, id := range ids {
		if _, err = s.getPublicSessionFile(ctx, v, id); err != nil {
			return fmt.Errorf("file %s is not available in this Session", id)
		}
	}
	if contract.Endpoint.InvocationMode == model.EndpointConversationMode && !s.publicSessionIsManaged(contract) && len(ids) > 0 {
		return fmt.Errorf("this Agent's conversational binding does not support file input")
	}
	if s.publicSessionIsManaged(contract) && len(input["input"]) > 0 {
		var messages []struct {
			Role    string           `json:"role"`
			Content []map[string]any `json:"content"`
		}
		if json.Unmarshal(input["input"], &messages) != nil || len(messages) == 0 || len(messages) > 100 {
			return fmt.Errorf("Managed Agent input must contain 1..100 user messages")
		}
		for _, m := range messages {
			if m.Role != "user" || len(m.Content) == 0 {
				return fmt.Errorf("input requires user messages with content blocks")
			}
			for _, block := range m.Content {
				switch block["type"] {
				case "text":
					if _, ok := block["text"].(string); !ok {
						return fmt.Errorf("text content requires text")
					}
				case "image", "audio", "video", "data":
				case "file":
					if id, ok := block["file_id"].(string); !ok || strings.TrimSpace(id) == "" {
						return fmt.Errorf("file content requires file_id")
					}
				default:
					return fmt.Errorf("unsupported input content type")
				}
			}
		}
	}
	return nil
}

// Materialize uploaded files in the selected Managed runtime while preserving
// stable public IDs and idempotency across retries and control-plane restarts.
func (s *Server) nativePublicSessionInput(ctx context.Context, inv *model.EndpointInvocation, owner string) (any, error) {
	contract, err := serviceapi.ReadContract(inv.Contract)
	if err != nil {
		return nil, err
	}
	v, err := sessionapi.Get(ctx, s.store, contract.Endpoint.Tenant, contract.PublicSessionID)
	if err != nil {
		return nil, err
	}
	var input map[string]json.RawMessage
	if err = json.Unmarshal(inv.Input, &input); err != nil {
		return nil, err
	}
	ids, err := sessionInputFiles(input)
	if err != nil {
		return nil, err
	}
	mapping := map[string]string{}
	for _, id := range ids {
		f, e := s.getPublicSessionFile(ctx, v, id)
		if e != nil {
			return nil, e
		}
		if f.NativeID == "" {
			if s.artifactProvider == nil {
				return nil, fmt.Errorf("file storage unavailable")
			}
			reader, _, e := s.artifactProvider.Open(ctx, f.StorageKey)
			if e != nil {
				return nil, e
			}
			h := http.Header{}
			h.Set("Idempotency-Key", f.ID.String())
			h.Set("X-File-Name", url.QueryEscape(f.Name))
			h.Set("Content-Type", f.ContentType)
			response, e := s.product.ManagedSessionResource(ctx, owner, inv.SessionID, "POST", "/files", h, reader)
			reader.Close()
			if e != nil {
				return nil, e
			}
			raw, e := io.ReadAll(io.LimitReader(response.Body, 1<<20))
			response.Body.Close()
			if e != nil {
				return nil, e
			}
			if response.StatusCode >= 300 {
				return nil, fmt.Errorf("file upload rejected: HTTP %d", response.StatusCode)
			}
			var file struct {
				ID string `json:"file_id"`
			}
			if json.Unmarshal(raw, &file) != nil || file.ID == "" {
				return nil, fmt.Errorf("runtime returned an invalid file")
			}
			f.NativeID = file.ID
			raw, _ = json.Marshal(f)
			if _, e = s.store.KV().Put(ctx, v.Tenant, sessionFilePath(v.ID), f.ID.String(), raw); e != nil {
				return nil, e
			}
		}
		mapping[id] = f.NativeID
	}
	var payload any
	_ = json.Unmarshal(inv.Input, &payload)
	var replace func(any)
	replace = func(x any) {
		switch obj := x.(type) {
		case map[string]any:
			if id, ok := obj["file_id"].(string); ok {
				if mapped := mapping[id]; mapped != "" {
					obj["file_id"] = mapped
				}
			}
			for _, value := range obj {
				replace(value)
			}
		case []any:
			for _, value := range obj {
				replace(value)
			}
		}
	}
	replace(payload)
	return payload, nil
}

func (s *Server) attachPublicSessionFiles(ctx context.Context, inv *model.EndpointInvocation, issueID uuid.UUID) error {
	contract, err := serviceapi.ReadContract(inv.Contract)
	if err != nil {
		return err
	}
	if contract.PublicSessionID == uuid.Nil {
		return nil
	}
	v, err := sessionapi.Get(ctx, s.store, contract.Endpoint.Tenant, contract.PublicSessionID)
	if err != nil {
		return err
	}
	var input map[string]json.RawMessage
	_ = json.Unmarshal(inv.Input, &input)
	ids, err := sessionInputFiles(input)
	if err != nil {
		return err
	}
	for _, id := range ids {
		f, e := s.getPublicSessionFile(ctx, v, id)
		if e != nil {
			return e
		}
		artifactID := uuid.NewSHA1(inv.ID, []byte("input-file:"+id))
		if _, _, e = s.store.Collaboration().GetArtifact(ctx, artifactID); e == nil {
			continue
		} else if !errors.Is(e, store.ErrNotFound) {
			return e
		}
		_, e = s.store.Collaboration().CreateArtifact(ctx, &model.Artifact{ID: artifactID, Tenant: v.Tenant, Namespace: v.Namespace, StorageProvider: s.artifactProvider.Name(), StorageKey: f.StorageKey, Filename: f.Name, ContentType: f.ContentType, SizeBytes: f.Size, Checksum: f.Checksum, Uploader: inv.Actor}, []model.ArtifactLink{{ArtifactID: artifactID, TargetType: "issue", TargetRef: issueID.String(), Relation: "attachment"}})
		if e != nil && !errors.Is(e, store.ErrConflict) {
			return e
		}
	}
	return nil
}

func publicSessionFileRequest(c *gin.Context) bool {
	return c.Request.Method == "POST" && strings.HasPrefix(c.Request.URL.Path, "/api/v1/agent-sessions/") && strings.HasSuffix(c.Request.URL.Path, "/files")
}

func (s *Server) publicSessionArtifactMetadata(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.publicSessionIsManaged(contract) {
		sessionUnsupported(c, "artifact metadata; use the Turn artifacts resource")
		return
	}
	snapshot, err := s.managedPublicSnapshot(c, v, contract, "")
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	artifacts, _ := snapshot["artifacts"].([]any)
	for _, raw := range artifacts {
		event, _ := raw.(map[string]any)
		data, _ := event["data"].(map[string]any)
		if data["artifact_id"] == c.Param("resourceId") {
			c.JSON(200, data)
			return
		}
	}
	c.JSON(404, ErrorResponse{Error: "artifact not found in this Session"})
}
