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
	"context"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

func (s *Server) setLocalDeveloper(c *gin.Context) {
	c.Set("userId", product.LocalDeveloperID)
	c.Set("username", product.LocalDeveloperID)
	c.Set("groups", product.LocalDeveloperClaims().Roles)
	c.Set(ctxConsoleAuth, true)
}

func (s *Server) localDevelopmentNamespace(ctx context.Context) (*model.Namespace, error) {
	n, err := s.ensureGlobalDefaultNamespace(ctx)
	if err != nil {
		return nil, err
	}
	// Override access only in this request's view, never in the stored policy.
	view := *n
	view.Kind = "shared"
	view.Owner = product.LocalDeveloperID
	view.Archived = false
	view.Members = map[string][]string{product.LocalDeveloperID: {"auditor"}}
	view.Groups = nil
	view.Resources = nil
	return &view, nil
}

func (s *Server) setLocalNamespaceAccess(c *gin.Context) bool {
	if s.store == nil {
		return true
	}
	n, err := s.localDevelopmentNamespace(c.Request.Context())
	if err != nil {
		s.accessFailure(c, err)
		return false
	}
	c.Request = c.Request.WithContext(store.WithWorkAccess(c.Request.Context(), store.WorkAccess{LocalDev: true}))
	c.Set(ctxNamespaceAccess, &namespaceAccess{User: product.LocalDeveloperID,
		Refs: []string{product.LocalDeveloperID}, Namespace: n, Roles: n.Roles(product.LocalDeveloperID)})
	return true
}

func (s *Server) localWorkContext(ctx context.Context) context.Context {
	if s.localDev {
		return store.WithWorkAccess(ctx, store.WorkAccess{LocalDev: true})
	}
	return ctx
}
