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
	"context"
	"encoding/json"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/gin-gonic/gin"
)

const sessionWebhookIndex = "service-session-webhook-index"
const sessionIndexTenant = "__agentscope_service_internal__"

func sessionWebhookExecution(v *sessionapi.Session) *model.EndpointInvocation {
	return &model.EndpointInvocation{ID: v.ID, EndpointID: v.ID, Contract: v.Contract, ApplicationID: v.ApplicationID, PrincipalRef: v.Principal, Status: model.EndpointInvocationRunning, CreatedAt: v.CreatedAt}
}
func (s *Server) publicSessionWebhookContext() gin.HandlerFunc {
	return func(c *gin.Context) {
		scope := "read"
		if c.Request.Method != "GET" {
			scope = "webhooks:write"
		}
		v, ok := s.loadPublicSession(c, scope)
		if !ok {
			c.Abort()
			return
		}
		c.Set(publicSessionContextKey, v)
		c.Set("public-session-webhook", true)
		if c.Request.Method == "POST" {
			// A durable discovery index lets another replica deliver Session hooks,
			// including after all Turns have ended and their workers are parked.
			raw, _ := json.Marshal(map[string]string{"tenant": v.Tenant, "session": v.ID.String()})
			if _, err := s.store.KV().Put(c, sessionIndexTenant, sessionWebhookIndex, v.ID.String(), raw); err != nil {
				s.writeControlPlaneError(c, err)
				c.Abort()
				return
			}
		}
		c.Next()
	}
}
func (s *Server) sweepPublicSessionWebhooks(ctx context.Context) error {
	for offset := 0; ; offset += 100 {
		rows, err := s.store.KV().Search(ctx, sessionIndexTenant, sessionWebhookIndex, 100, offset)
		if err != nil {
			return err
		}
		for _, row := range rows {
			var ref struct {
				Tenant  string `json:"tenant"`
				Session string `json:"session"`
			}
			if err = json.Unmarshal(row.Value, &ref); err != nil {
				return err
			}
			var v sessionapi.Session
			record, e := s.store.KV().Get(ctx, ref.Tenant, sessionapi.Path, ref.Session)
			if e != nil {
				continue
			}
			if e = json.Unmarshal(record.Value, &v); e != nil {
				return e
			}
			if v.Status == "deleted" {
				continue
			}
			c, e := serviceapi.ReadContract(v.Contract)
			if e != nil {
				return e
			}
			if e = s.projectPublicSession(ctx, &v); e != nil {
				return e
			}
			if e = s.deliverServiceWebhooks(ctx, sessionWebhookExecution(&v), &c.Endpoint); e != nil {
				return e
			}
		}
		if len(rows) < 100 {
			return nil
		}
	}
}
func (s *Server) runPublicSessionWebhooks(ctx context.Context) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.sweepPublicSessionWebhooks(ctx); err != nil && ctx.Err() == nil {
				gin.DefaultErrorWriter.Write([]byte("Session webhook delivery: " + err.Error() + "\n"))
			}
		}
	}
}

func (s *Server) schedulePublicWebhook(c *gin.Context, inv *model.EndpointInvocation) error {
	if c.GetBool("public-session-webhook") {
		return nil
	}
	return s.store.Endpoints().ScheduleInvocation(c, inv.ID, time.Now().UTC())
}
