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
	"encoding/json"
	"io"
	"net/http"

	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/gin-gonic/gin"
)

// Explicit mounts require the caller's resource access, independently of the
// target Agent's delegated default resources and its internal runtime owner.
func (s *Server) validatePublicSessionOptions(c *gin.Context, raw []byte) bool {
	a := accessFrom(c)
	if a == nil {
		return true
	}
	var options struct {
		Environment string   `json:"environmentId"`
		Memory      []string `json:"memoryStoreIds"`
		Vaults      []string `json:"vaultIds"`
	}
	if json.Unmarshal(raw, &options) != nil {
		c.JSON(400, ErrorResponse{Error: "invalid Session settings"})
		return false
	}
	keys := []string{}
	if options.Environment != "" {
		keys = append(keys, "environment:"+options.Environment)
	}
	for _, id := range options.Memory {
		keys = append(keys, "memory:"+id)
	}
	for _, id := range options.Vaults {
		keys = append(keys, "vault:"+id)
	}
	for _, key := range keys {
		if err := s.checkResourceUse(c, a.Namespace, a.User, key); err != nil {
			c.JSON(403, ErrorResponse{Error: err.Error()})
			return false
		}
	}
	return true
}

func (s *Server) publicSessionView(ctx context.Context, v *sessionapi.Session) map[string]any {
	view := v.View()
	c, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		return view
	}
	if v.Target.Type == "agent" {
		view["agentId"] = v.Target.ID.String()
		view["agentVersion"] = v.Target.Version
	}
	view["runtimeKind"] = v.Target.Type
	if policy := c.Policies[v.Target.ID.String()]; policy != nil && len(policy.Candidates) > 0 {
		kind := policy.Candidates[0].Binding.Kind
		view["runtimeKind"] = string(kind)
		for _, candidate := range policy.Candidates {
			if candidate.Binding.Kind != kind {
				view["runtimeKind"] = "mixed"
				break
			}
		}
	}
	view["environmentId"] = c.EnvironmentID
	var options map[string]any
	_ = json.Unmarshal(c.SessionOptions, &options)
	for _, key := range []string{"memoryStoreIds", "vaultIds", "agentOverrides"} {
		if value, ok := options[key]; ok {
			view[key] = value
		}
	}
	if s.product != nil && s.publicSessionIsManaged(c) {
		if _, e := s.store.Endpoints().GetConversation(ctx, v.ID); e == nil {
			owner, native, e := s.publicNativeSession(ctx, v, c)
			if e == nil {
				if row, e := s.product.ManagedSessionView(ctx, owner, native); e == nil {
					for _, key := range []string{"environmentId", "memoryStoreIds", "vaultIds", "agentOverridesJson", "stopReason"} {
						view[key] = row[key]
					}
					view["runtimeStatus"] = row["status"]
				}
			}
		}
	}
	return view
}
func (s *Server) patchPublicSession(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "interact")
	if !ok {
		return
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.publicSessionIsManaged(contract) {
		sessionUnsupported(c, "runtime settings")
		return
	}
	if v.Status != "active" {
		c.JSON(409, ErrorResponse{Error: "Session is not active"})
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 1<<20))
	if err != nil {
		c.JSON(413, ErrorResponse{Error: "Session settings too large"})
		return
	}
	if !s.validatePublicSessionOptions(c, raw) {
		return
	}
	c.Request.Body = io.NopCloser(bytes.NewReader(raw))
	owner, native, err := s.publicNativeSession(c, v, contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err = s.product.UpdateManagedServiceSession(c, owner, native); err != nil {
		return
	}
	c.JSON(200, s.publicSessionView(c, v))
}
