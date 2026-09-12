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

package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/gin-gonic/gin"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/worksource"
)

type githubWorkSourceConfig struct {
	WebhookSecret string `json:"webhookSecret"`
	Token         string `json:"token,omitempty"`
}

func (s *Server) createWorkSource(c *gin.Context) {
	var in controlmodel.WorkSource
	if err := c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if in.Tenant == "" {
		in.Tenant = "default"
	}
	if in.Namespace == "" {
		in.Namespace = defaultNamespace
	}
	if in.Kind != "github" || in.Name == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "kind=github and name are required"})
		return
	}
	in.Enabled = true
	v, err := s.store.WorkSources().CreateWorkSource(c.Request.Context(), &in)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"workSource": redactWorkSource(v)})
}
func (s *Server) listWorkSources(c *gin.Context) {
	tenant, namespace := c.Query("tenant"), c.Query("namespace")
	if tenant == "" {
		tenant = "default"
	}
	if namespace == "" {
		namespace = defaultNamespace
	}
	items, err := s.store.WorkSources().ListWorkSources(c.Request.Context(), tenant, namespace)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	for i := range items {
		items[i] = redactWorkSource(items[i])
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}
func (s *Server) getWorkSource(c *gin.Context) {
	id, ok := parseUUIDParam(c, "workSourceId")
	if !ok {
		return
	}
	v, err := s.store.WorkSources().GetWorkSource(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"workSource": redactWorkSource(v)})
}
func (s *Server) patchWorkSource(c *gin.Context) {
	id, ok := parseUUIDParam(c, "workSourceId")
	if !ok {
		return
	}
	current, err := s.store.WorkSources().GetWorkSource(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	var in struct {
		Name          *string          `json:"name"`
		Configuration *json.RawMessage `json:"configuration"`
		Enabled       *bool            `json:"enabled"`
		Version       int64            `json:"version"`
	}
	if err = c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if in.Version == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "version is required"})
		return
	}
	if in.Name != nil {
		current.Name = *in.Name
	}
	if in.Configuration != nil {
		configuration, mergeErr := preserveRedactedWorkSourceSecrets(current.Configuration, *in.Configuration)
		if mergeErr != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: mergeErr.Error()})
			return
		}
		current.Configuration = configuration
	}
	if in.Enabled != nil {
		current.Enabled = *in.Enabled
	}
	v, err := s.store.WorkSources().UpdateWorkSource(c.Request.Context(), current, in.Version)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"workSource": redactWorkSource(v)})
}

func preserveRedactedWorkSourceSecrets(current, replacement json.RawMessage) (json.RawMessage, error) {
	var oldConfig, nextConfig map[string]any
	if err := json.Unmarshal(replacement, &nextConfig); err != nil {
		return nil, errors.New("configuration must be a JSON object")
	}
	_ = json.Unmarshal(current, &oldConfig)
	for _, key := range []string{"token", "webhookSecret"} {
		if value, exists := nextConfig[key]; exists && value == "***" {
			if original, ok := oldConfig[key]; ok {
				nextConfig[key] = original
			} else {
				delete(nextConfig, key)
			}
		}
	}
	return json.Marshal(nextConfig)
}

func redactWorkSource(in *controlmodel.WorkSource) *controlmodel.WorkSource {
	if in == nil {
		return nil
	}
	out := *in
	var cfg map[string]any
	if json.Unmarshal(in.Configuration, &cfg) == nil {
		if _, ok := cfg["webhookSecret"]; ok {
			cfg["webhookSecret"] = "***"
		}
		if _, ok := cfg["token"]; ok {
			cfg["token"] = "***"
		}
		out.Configuration, _ = json.Marshal(cfg)
	}
	return &out
}

func (s *Server) flushWorkSourceCommentOutbox(c *gin.Context) {
	if accessFrom(c) != nil {
		c.JSON(403, ErrorResponse{Error: "global outbox flush requires an infrastructure service identity"})
		return
	}
	if s.workSources == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "Work Source service is unavailable"})
		return
	}
	published, err := s.workSources.FlushPendingComments(c.Request.Context(), 100)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"published": published})
}

func (s *Server) reconcileWorkSource(c *gin.Context) {
	if s.workSources == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "Work Source service is unavailable"})
		return
	}
	id, ok := parseUUIDParam(c, "workSourceId")
	if !ok {
		return
	}
	source, err := s.store.WorkSources().GetWorkSource(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	adapter, found := s.workSources.Adapters.Resolve(source.Kind)
	if !found {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "unsupported Work Source kind"})
		return
	}
	if err = adapter.Reconcile(c.Request.Context(), source); err != nil {
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"reconciled": true})
}

func (s *Server) githubWorkSourceWebhook(c *gin.Context) {
	id, ok := parseUUIDParam(c, "workSourceId")
	if !ok {
		return
	}
	source, err := s.store.WorkSources().GetWorkSource(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 2<<20))
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid webhook payload"})
		return
	}
	var cfg githubWorkSourceConfig
	if json.Unmarshal(source.Configuration, &cfg) != nil || !worksource.VerifyGitHubSignature(cfg.WebhookSecret, body, c.GetHeader("X-Hub-Signature-256")) {
		c.JSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid GitHub signature"})
		return
	}
	delivery := c.GetHeader("X-GitHub-Delivery")
	if delivery == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "X-GitHub-Delivery is required"})
		return
	}
	err = s.workSources.HandleEvent(c.Request.Context(), source, worksource.Event{DeliveryID: delivery, EventType: c.GetHeader("X-GitHub-Event"), Payload: body})
	if err != nil {
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: err.Error()})
		return
	}
	c.Status(http.StatusAccepted)
}
