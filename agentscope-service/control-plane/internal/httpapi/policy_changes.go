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
	"errors"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/policychanges"
)

func (s *Server) claimPolicyChanges(c *gin.Context) {
	repos := map[string]policychanges.Repository{"control": s.store.PolicyChanges()}
	if s.product != nil {
		repos["product"] = s.product.PolicyChanges()
	}
	out := []gin.H{}
	for source, repo := range repos {
		rows, err := repo.Claim(c.Request.Context(), time.Now().UTC(), 30*time.Second, 8)
		if err != nil {
			c.JSON(503, ErrorResponse{Error: "policy changes unavailable"})
			return
		}
		for _, v := range rows {
			out = append(out, gin.H{"source": source, "id": v.ID, "tenant": v.Tenant, "namespace": v.Namespace, "application_id": v.ApplicationID, "claim_token": v.ClaimToken, "schema_version": 1})
		}
	}
	c.JSON(200, gin.H{"changes": out})
}
func (s *Server) acknowledgePolicyChange(c *gin.Context) {
	var in struct {
		Source     string    `json:"source"`
		ID         uuid.UUID `json:"id"`
		ClaimToken string    `json:"claim_token"`
	}
	if c.ShouldBindJSON(&in) != nil || in.ID == uuid.Nil || len(in.ClaimToken) != 36 {
		c.Status(400)
		return
	}
	var repo policychanges.Repository
	switch in.Source {
	case "control":
		repo = s.store.PolicyChanges()
	case "product":
		if s.product != nil {
			repo = s.product.PolicyChanges()
		}
	}
	if repo == nil {
		c.Status(400)
		return
	}
	err := repo.Acknowledge(c.Request.Context(), in.ID, in.ClaimToken)
	if errors.Is(err, policychanges.ErrClaim) {
		c.Status(409)
		return
	}
	if err != nil {
		c.Status(503)
		return
	}
	c.Status(204)
}
