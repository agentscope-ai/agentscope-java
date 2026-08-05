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
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/spring-ai-alibaba/aistio/internal/discovery"
)

func (s *Server) adoptAgent(c *gin.Context) {
	var req struct {
		DeploymentName string `json:"deploymentName" binding:"required"`
		Namespace      string `json:"namespace"`
		AgentName      string `json:"agentName"`
		Runtime        string `json:"runtime"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	namespace := req.Namespace
	if namespace == "" {
		namespace = defaultNamespace
	}

	adopter := discovery.NewAdopter(s.client)
	result, err := adopter.Adopt(c.Request.Context(), discovery.AdoptRequest{
		DeploymentName: req.DeploymentName,
		Namespace:      namespace,
		AgentName:      req.AgentName,
		Runtime:        req.Runtime,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, result)
}
