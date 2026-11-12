// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/product"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/sessionapi"
)

type nativeSessionEventPage struct {
	Data []map[string]any `json:"data"`
	Next string           `json:"next_cursor"`
	More bool             `json:"has_more"`
}

func (s *Server) nativeSessionEvents(ctx context.Context, v *sessionapi.Session, contract *serviceapi.Contract, owner, after string, limit int) (nativeSessionEventPage, error) {
	origin, err := s.nativePublicSessionOrigin(ctx, v, contract, owner, "session-events:"+v.ID.String())
	if err != nil {
		return nativeSessionEventPage{}, err
	}
	raw, err := s.product.ManagedRuntimeRequest(ctx, owner, product.ManagedRuntimeCall{Context: origin, Operation: "events", After: after, Limit: limit})
	if err != nil {
		return nativeSessionEventPage{}, err
	}
	return decodeNativeSessionEvents(v.ID.String(), raw, after)
}

func decodeNativeSessionEvents(session string, raw json.RawMessage, after string) (nativeSessionEventPage, error) {
	var page nativeSessionEventPage
	if err := json.Unmarshal(raw, &page); err != nil {
		return page, err
	}
	if page.Next == "" || page.More && page.Next == after {
		return page, fmt.Errorf("native Session event cursor did not advance")
	}
	for _, event := range page.Data {
		if event["session_id"] != session {
			return page, fmt.Errorf("native event differs from its Session")
		}
		for _, field := range []string{"cursor", "id", "type"} {
			value := firstPayloadString(event, field)
			if value == "" || len(value) > 4096 || strings.ContainsAny(value, "\r\n") {
				return page, fmt.Errorf("invalid native event envelope")
			}
		}
	}
	return page, nil
}

func (s *Server) serveNativeSessionEvents(c *gin.Context, v *sessionapi.Session, contract *serviceapi.Contract, stream bool) {
	limit := queryInt(c, "limit", 200)
	if limit < 1 || limit > 256 {
		c.JSON(400, ErrorResponse{Error: "limit must be 1..256"})
		return
	}
	owner, _, err := s.publicNativeSession(c, v, contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	after := serviceAfter(c)
	page, err := s.nativeSessionEvents(c, v, contract, owner, after, limit)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !stream {
		c.JSON(200, page)
		return
	}
	prepareEventStream(c)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	heartbeat := time.Now()
	for {
		for _, event := range page.Data {
			raw, err := json.Marshal(event)
			if err != nil {
				return
			}
			if _, err = fmt.Fprintf(c.Writer, "id: %s\nevent: %s\ndata: %s\n\n", event["cursor"], event["type"], raw); err != nil {
				return
			}
		}
		c.Writer.Flush()
		after = page.Next
		if !page.More {
			select {
			case <-c.Request.Context().Done():
				return
			case <-ticker.C:
			}
		}
		page, err = s.nativeSessionEvents(c, v, contract, owner, after, limit)
		if err != nil {
			return
		}
		if time.Since(heartbeat) >= 15*time.Second {
			if _, err = fmt.Fprint(c.Writer, ": heartbeat\n\n"); err != nil {
				return
			}
			c.Writer.Flush()
			heartbeat = time.Now()
		}
	}
}
