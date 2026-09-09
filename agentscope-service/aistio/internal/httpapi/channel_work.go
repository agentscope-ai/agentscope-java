// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package httpapi

import (
	"context"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"strings"
)

func (s *Server) configureChannelWork() {
	s.product.SetChannelWorkRuntime(&product.ChannelWorkRuntime{Store: s.store,
		AuthorizeTarget: func(ctx context.Context, n *model.Namespace, user, kind, id string) error {
			if len(n.Resources) == 0 {
				return nil
			}
			return s.checkResourceUse(ctx, n, user, kind+":"+id)
		},
		Namespace: func(ctx context.Context, owner string) (*model.Namespace, error) {
			if strings.HasPrefix(owner, "namespace:") {
				p := strings.Split(owner, ":")
				if len(p) != 3 {
					return nil, store.ErrNotFound
				}
				return s.store.Access().GetNamespace(ctx, p[1], p[2])
			}
			return s.ensurePersonalNamespace(ctx, owner)
		},
		Target: func(ctx context.Context, n *model.Namespace, kind, ref string) error {
			switch kind {
			case "agent":
				_, e := s.activeAgentInScope(ctx, n.Tenant, n.Name, ref)
				return e
			case "team":
				id, e := uuid.Parse(ref)
				if e != nil {
					return store.ErrNotFound
				}
				team, e := s.store.Collaboration().GetTeam(ctx, id)
				if e != nil {
					return e
				}
				if team.Tenant != n.Tenant || team.Namespace != n.Name || team.Status != "active" {
					return store.ErrNotFound
				}
				_, e = s.activeAgentInScope(ctx, n.Tenant, n.Name, team.LeaderAgentRef)
				return e
			default:
				return store.ErrNotFound
			}
		},
	})
}
