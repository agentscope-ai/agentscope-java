// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
package httpapi

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/agentservice"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
	"mime"
	"net/http"
	"strings"
	"time"
)

// Each observation reads at most sixteen deliverables. Repeated rounds catch
// uploads that committed behind a keyset cursor; immutable IDs avoid duplicate
// facts across rounds or writer generations. Cursor capacity is explicit.
func (b *agentServiceNativeBackend) observeArtifacts(ctx context.Context, turn agentservice.Turn, cursor *agentServiceNativeCursor, progress *agentservice.Progress) (bool, error) {
	limit := min(16, agentServiceObservationFactLimit-len(progress.Facts))
	if limit <= 0 {
		return false, nil
	}
	items, err := b.server.store.Collaboration().ListRuntimeArtifacts(ctx, store.RuntimeArtifactFilter{Tenant: turn.Command.Context.Tenant, Namespace: turn.Command.Context.Namespace, AgentServiceCommandID: turn.Command.Context.CommandID,
		AfterCreatedAt: cursor.ArtifactAfterAt, AfterID: cursor.ArtifactAfterID, Limit: limit})
	if err != nil {
		return false, err
	}
	if cursor.Artifacts == nil {
		cursor.Artifacts = map[string]bool{}
	}
	for _, item := range items {
		id := item.ID.String()
		if !cursor.Artifacts[id] {
			if len(cursor.Artifacts) >= 8192 {
				return false, agentServiceObservationCapacityError("native_artifacts")
			}
			payload := map[string]any{
				"artifact_id": id, "name": item.Filename, "media_type": item.ContentType, "size": item.SizeBytes,
				"download_url": "/api/v1/agent-sessions/" + turn.Command.Context.SessionID + "/turns/" + turn.Command.Context.CommandID + "/artifacts/" + id + "/content"}
			if digest, ok := strings.CutPrefix(item.Checksum, "sha256:"); ok && len(digest) == 64 {
				if _, err := hex.DecodeString(digest); err == nil {
					payload["sha256"] = strings.ToLower(digest)
				}
			}
			progress.Facts = append(progress.Facts, runtimeprotocol.RuntimeFact{EventID: "native:artifact:" + id, Type: "artifact.published", Payload: payload})
			cursor.Artifacts[id] = true
		}
		at := item.CreatedAt
		cursor.ArtifactAfterAt, cursor.ArtifactAfterID = &at, item.ID
	}
	drained := len(items) < limit
	if drained {
		cursor.ArtifactAfterAt, cursor.ArtifactAfterID = nil, uuid.Nil
	}
	return drained, nil
}

// Agent Service authenticates the original reader. The execution listener checks the
// immutable Turn/scope binding again, without trusting a caller-provided URI.
func (s *Server) downloadAgentServiceRuntimeArtifact(c *gin.Context) {
	select {
	case s.artifactTransfers <- struct{}{}:
		defer func() { <-s.artifactTransfers }()
	default:
		c.Status(http.StatusTooManyRequests)
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 30*time.Second)
	defer cancel()
	writer := http.NewResponseController(c.Writer)
	if err := writer.SetWriteDeadline(time.Now().Add(30 * time.Second)); err != nil && !errors.Is(err, http.ErrNotSupported) {
		c.Status(http.StatusServiceUnavailable)
		return
	}
	defer writer.SetWriteDeadline(time.Time{})
	turn, err := s.store.RuntimeAgentService().Turn(ctx, c.Param("turn"))
	if err != nil {
		runtimeHTTPError(c, err)
		return
	}
	if turn.Command.Context.SessionID != c.Param("session") {
		c.Status(404)
		return
	}
	id, err := uuid.Parse(c.Param("artifact"))
	if err != nil {
		c.Status(404)
		return
	}
	item, _, err := s.store.Collaboration().GetArtifact(ctx, id)
	if err != nil {
		runtimeHTTPError(c, err)
		return
	}
	scope := turn.Command.Context
	if item.AgentServiceCommandID != scope.CommandID || item.Tenant != scope.Tenant || item.Namespace != scope.Namespace {
		c.Status(404)
		return
	}
	if item.ExpiresAt != nil && !item.ExpiresAt.After(time.Now()) {
		c.Status(http.StatusGone)
		return
	}
	if s.artifactProvider == nil || s.artifactProvider.Name() != item.StorageProvider {
		c.Status(http.StatusServiceUnavailable)
		return
	}
	if item.SizeBytes > 100<<20 {
		c.Status(http.StatusRequestEntityTooLarge)
		return
	}
	reader, info, err := s.artifactProvider.Open(ctx, item.StorageKey)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			c.Status(http.StatusServiceUnavailable)
			return
		}
		c.Status(404)
		return
	}
	defer reader.Close()
	if info.Size != item.SizeBytes || info.Checksum != item.Checksum {
		c.Status(http.StatusUnprocessableEntity)
		return
	}
	if info.Size > 100<<20 {
		c.Status(http.StatusRequestEntityTooLarge)
		return
	}
	media := item.ContentType
	if media == "" {
		media = "application/octet-stream"
	}
	c.Header("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": item.Filename}))
	c.Header("Cache-Control", "private, no-store")
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("ETag", fmt.Sprintf("%q", item.Checksum))
	c.DataFromReader(http.StatusOK, info.Size, media, reader, nil)
}
