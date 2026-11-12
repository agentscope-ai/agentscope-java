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
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/product"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
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
}

func sessionFilePath(id uuid.UUID) string           { return "service-session-files/" + id.String() }
func (f publicSessionFile) view() publicSessionFile { f.StorageKey = ""; return f }
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
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.publicSessionIsManaged(contract) {
		owner, _, err := s.publicNativeSession(c, v, contract)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		origin, err := s.ensureNativePublicSession(c, v, contract, owner, key)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		raw, err := s.product.ManagedRuntimeUpload(c, owner, origin, name, c.GetHeader("Content-Type"), c.Request.Body)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		c.Data(http.StatusCreated, "application/json", raw)
		return
	}
	if s.artifactProvider == nil {
		c.JSON(503, ErrorResponse{Error: "file storage is unavailable"})
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
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.publicSessionIsManaged(contract) {
		s.publicSessionNativeResource(c)
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
func (s *Server) publicSessionFileMetadata(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.publicSessionIsManaged(contract) {
		s.publicSessionNativeResource(c)
		return
	}
	file, err := s.getPublicSessionFile(c, v, c.Param("fileId"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, file.view())
}

func (s *Server) publicSessionFileContent(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.publicSessionIsManaged(contract) {
		s.publicSessionNativeResource(c)
		return
	}
	f, err := s.getPublicSessionFile(c, v, c.Param("fileId"))
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
	if s.publicSessionIsManaged(contract) {
		policy := contract.Policies[v.Target.ID.String()]
		candidate := policy.Candidates[0].Binding
		owner := candidate.ManagedOwnerRef
		if owner == "" {
			binding, err := s.store.AgentCatalog().GetBinding(ctx, candidate.BindingID)
			if err != nil {
				return "", "", err
			}
			var cfg model.ManagedBindingConfiguration
			if json.Unmarshal(binding.Configuration, &cfg) != nil || cfg.OwnerRef == "" {
				return "", "", fmt.Errorf("invalid Managed resource binding")
			}
			owner = cfg.OwnerRef
		}
		return owner, v.ID.String(), nil
	}

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
	if c.Request.Method == http.MethodPost && strings.HasSuffix(c.Request.URL.Path, "/cancel") {
		scope = "cancel"
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
	owner, _, err := s.publicNativeSession(c, v, contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	origin, err := s.ensureNativePublicSession(c, v, contract, owner, c.GetHeader("Idempotency-Key"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if c.Request.Method == http.MethodGet && suffix == "/files/"+c.Param("fileId")+"/content" {
		s.serveNativeSessionFile(c, owner, origin, c.Param("fileId"))
		return
	}
	if c.Request.Method == http.MethodGet && suffix == "/artifacts/"+c.Param("resourceId")+"/content" {
		raw, e := s.product.ManagedRuntimeRequest(c, owner, product.ManagedRuntimeCall{Context: origin, Operation: "artifact", ResourceID: c.Param("resourceId")})
		if e != nil {
			s.writeControlPlaneError(c, e)
			return
		}
		var artifact struct {
			File string `json:"file_id"`
		}
		if json.Unmarshal(raw, &artifact) != nil {
			c.JSON(502, ErrorResponse{Error: "invalid artifact response"})
			return
		}
		if artifact.File == "" {
			c.JSON(422, ErrorResponse{Error: "external artifact content is available at its URI"})
			return
		}
		s.serveNativeSessionFile(c, owner, origin, artifact.File)
		return
	}
	call, err := nativeSessionResourceCall(c, suffix, origin)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if call.Operation == "" {
		sessionUnsupported(c, suffix)
		return
	}
	raw, err := s.product.ManagedRuntimeRequest(c, owner, call)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	status := http.StatusOK
	if call.Operation == "publish_artifact" {
		status = http.StatusCreated
	}
	if call.Operation == "configure_budget" || call.Operation == "restore_checkpoint" {
		status = http.StatusAccepted
	}
	c.Data(status, "application/json", raw)
}

// Explicit operations preserve native IDs, pinned snapshots and the authenticated request origin.
func nativeSessionResourceCall(c *gin.Context, suffix string, origin runtimeprotocol.Context) (product.ManagedRuntimeCall, error) {
	call := product.ManagedRuntimeCall{Context: origin, After: c.Query("after"), SnapshotID: c.Query("snapshot_id"), Limit: 100}
	if text := c.Query("limit"); text != "" {
		limit, err := strconv.Atoi(text)
		if err != nil || limit < 1 || limit > 256 {
			return call, &product.ManagedServiceError{StatusCode: 400, Message: "limit must contain an integer from 1 to 256"}
		}
		call.Limit = limit
	}
	if turn := c.Query("turn"); turn != "" {
		call.Context.TurnID = &turn
	}
	switch c.Request.Method {
	case http.MethodGet:
		switch suffix {
		case "/capabilities":
			call.Operation = "capabilities"
		case "/budget":
			call.Operation = "budget"
		case "/usage":
			call.Operation = "usage"
		case "/artifacts":
			call.Operation = "artifacts"
		case "/checkpoints":
			call.Operation, call.Through = "checkpoints", c.Query("through")
			if c.Query("limit") == "" {
				call.Limit = 20
			}
		case "/files":
			call.Operation = "files"
			if c.Query("limit") == "" {
				call.Limit = 50
			}
			if text := c.Query("offset"); text != "" {
				value, err := strconv.Atoi(text)
				if err != nil || value < 0 || value > 256 {
					return call, &product.ManagedServiceError{StatusCode: 400, Message: "offset must contain an integer from 0 to 256"}
				}
				call.Offset = value
			}
		case "/items", "/tools", "/inputs", "/required-actions":
			call.Operation = "resource_page"
			call.ResourceKind = strings.ReplaceAll(strings.TrimPrefix(suffix, "/"), "-", "_")
		default:
			if id := c.Param("resourceId"); id != "" && suffix == "/checkpoint-operations/"+id {
				call.Operation, call.ReceiptID = "checkpoint_operation", id
			}
			if id := c.Param("resourceId"); id != "" && suffix == "/checkpoints/"+id {
				call.Operation, call.ResourceID = "checkpoint", id
			}
			if id := c.Param("fileId"); id != "" && suffix == "/files/"+id {
				call.Operation, call.ResourceID = "file", id
			}
			if id := c.Param("resourceId"); id != "" && suffix == "/artifacts/"+id {
				call.Operation, call.ResourceID = "artifact", id
			}
		}
	case http.MethodPut:
		if suffix == "/budget" {
			call.Operation, call.ExpectedRevision = "configure_budget", c.GetHeader("If-Match")
		}
	case http.MethodPost:
		if suffix == "/artifacts" {
			call.Operation = "publish_artifact"
		}
		if suffix == "/checkpoints/restore" {
			call.Operation = "restore_checkpoint"
		}
		if id := c.Param("resourceId"); id != "" && suffix == "/checkpoint-operations/"+id+"/cancel" {
			call.Operation, call.ReceiptID = "cancel_checkpoint_operation", id
		}
	case http.MethodDelete:
		if id := c.Param("resourceId"); id != "" && suffix == "/artifacts/"+id {
			call.Operation, call.ResourceID = "delete_artifact", id
		}
	}
	if call.Operation == "publish_artifact" || call.Operation == "restore_checkpoint" {
		key := c.GetHeader("Idempotency-Key")
		if key == "" || len(key) > 256 {
			return call, &product.ManagedServiceError{StatusCode: 400, Message: "Idempotency-Key must contain 1..256 characters"}
		}
	}
	if call.Operation == "configure_budget" || call.Operation == "publish_artifact" || call.Operation == "restore_checkpoint" {
		var body map[string]any
		decoder := json.NewDecoder(http.MaxBytesReader(c.Writer, c.Request.Body, 16<<20))
		if err := decoder.Decode(&body); err != nil || body == nil {
			return call, &product.ManagedServiceError{StatusCode: 400, Message: "a JSON object body is required"}
		}
		if err := decoder.Decode(new(any)); err != io.EOF {
			return call, &product.ManagedServiceError{StatusCode: 400, Message: "a single JSON body is required"}
		}
		call.Payload = body
	}
	return call, nil
}

func (s *Server) serveNativeSessionFile(c *gin.Context, owner string, origin runtimeprotocol.Context, file string) {
	response, err := s.product.ManagedRuntimeFileContent(c, owner, origin, file)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	defer response.Body.Close()
	headers := map[string]string{"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"}
	if value := response.Header.Get("Content-Disposition"); value != "" {
		headers["Content-Disposition"] = value
	}
	c.DataFromReader(http.StatusOK, response.ContentLength, response.Header.Get("Content-Type"), response.Body, headers)
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
	if !s.publicSessionIsManaged(contract) {
		for _, id := range ids {
			if _, err = s.getPublicSessionFile(ctx, v, id); err != nil {
				return fmt.Errorf("file %s is not available in this Session", id)
			}
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

func (s *Server) attachPublicSessionFiles(ctx context.Context, inv *model.EndpointInvocation, issueID uuid.UUID) error {
	contract, err := serviceapi.ReadContract(inv.Contract)
	if err != nil {
		return err
	}
	if contract.PublicSessionID == uuid.Nil {
		return nil
	}
	rawInput, err := serviceapi.InvocationInput(ctx, s.store, inv)
	if err != nil {
		return err
	}
	var input map[string]json.RawMessage
	_ = json.Unmarshal(rawInput, &input)
	ids, err := sessionInputFiles(input)
	if err != nil {
		return err
	}
	if len(ids) == 0 {
		return nil
	}
	v, err := sessionapi.Get(ctx, s.store, contract.Endpoint.Tenant, contract.PublicSessionID)
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

func publicSessionProtocolRequest(c *gin.Context) bool {
	return c.Request.URL.Path == "/api/v1/agent-sessions" || strings.HasPrefix(c.Request.URL.Path, "/api/v1/agent-sessions/")
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
	s.publicSessionNativeResource(c)
}
