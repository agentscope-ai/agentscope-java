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

package httpapi

import (
	"encoding/json"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

func (s *Server) listAgentInstances(c *gin.Context) {
	var agentID uuid.UUID
	if raw := c.Query("agentId"); raw != "" {
		var err error
		agentID, err = uuid.Parse(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid agentId"})
			return
		}
	}
	instances, err := s.store.RuntimeRegistry().ListAgentInstances(c.Request.Context(),
		c.Query("tenant"), c.Query("namespace"), agentID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": instances})
}

func (s *Server) getAgentInstance(c *gin.Context) {
	id, ok := parseUUIDParam(c, "instanceId")
	if !ok {
		return
	}
	instance, err := s.store.RuntimeRegistry().GetAgentInstance(c.Request.Context(), id)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"instance": instance})
}

type runtimeProfileRequest struct {
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	Name          string          `json:"name"`
	Provider      string          `json:"provider"`
	Runtime       string          `json:"runtime,omitempty"`
	Configuration json.RawMessage `json:"configuration,omitempty"`
	Requirements  json.RawMessage `json:"requirements,omitempty"`
}

func (s *Server) upsertRuntimeProfile(c *gin.Context) {
	var req runtimeProfileRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if pathName := c.Param("name"); pathName != "" {
		req.Name = pathName
	}
	if req.Tenant == "" {
		req.Tenant = "default"
	}
	if req.Namespace == "" {
		req.Namespace = defaultNamespace
	}
	profile, err := s.store.RuntimeRegistry().UpsertRuntimeProfile(c.Request.Context(), &controlmodel.RuntimeProfile{
		Tenant: req.Tenant, Namespace: req.Namespace, Name: req.Name, Provider: req.Provider,
		Runtime: req.Runtime, Configuration: req.Configuration, Requirements: req.Requirements,
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"profile": profile})
}

func (s *Server) listRuntimeProfiles(c *gin.Context) {
	profiles, err := s.store.RuntimeRegistry().ListRuntimeProfiles(c.Request.Context(), c.Query("tenant"), c.Query("namespace"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": profiles})
}

func (s *Server) getRuntimeProfile(c *gin.Context) {
	profile, err := s.store.RuntimeRegistry().GetRuntimeProfile(c.Request.Context(),
		c.DefaultQuery("tenant", "default"), c.DefaultQuery("namespace", defaultNamespace), c.Param("name"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"profile": profile})
}

type runtimePoolRequest struct {
	Tenant        string          `json:"tenant"`
	Namespace     string          `json:"namespace"`
	Name          string          `json:"name"`
	HostSelector  json.RawMessage `json:"hostSelector,omitempty"`
	Configuration json.RawMessage `json:"configuration,omitempty"`
}

func (s *Server) upsertRuntimePool(c *gin.Context) {
	var req runtimePoolRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}
	if pathName := c.Param("name"); pathName != "" {
		req.Name = pathName
	}
	if req.Tenant == "" {
		req.Tenant = "default"
	}
	if req.Namespace == "" {
		req.Namespace = defaultNamespace
	}
	pool, err := s.store.RuntimeRegistry().UpsertRuntimePool(c.Request.Context(), &controlmodel.RuntimePool{
		Tenant: req.Tenant, Namespace: req.Namespace, Name: req.Name,
		HostSelector: req.HostSelector, Configuration: req.Configuration,
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"pool": pool})
}

func (s *Server) listRuntimePools(c *gin.Context) {
	pools, err := s.store.RuntimeRegistry().ListRuntimePools(c.Request.Context(), c.Query("tenant"), c.Query("namespace"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": pools})
}

func (s *Server) getRuntimePool(c *gin.Context) {
	pool, err := s.store.RuntimeRegistry().GetRuntimePool(c.Request.Context(),
		c.DefaultQuery("tenant", "default"), c.DefaultQuery("namespace", defaultNamespace), c.Param("name"))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"pool": pool})
}
