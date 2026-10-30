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
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

func (s *Server) publicManagedInput(c *gin.Context, inv *model.EndpointInvocation, ep *model.Endpoint, kind string) {
	key := c.GetHeader("Idempotency-Key")
	if key == "" {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key is required"})
		return
	}
	turn, err := s.nativeServiceTurn(c, ep, inv)
	if err != nil {
		c.JSON(409, ErrorResponse{Error: "Turn has not started"})
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 16<<20))
	if err != nil {
		c.Status(413)
		return
	}
	copy := *inv
	copy.Input = raw
	body, err := s.nativePublicSessionInput(c, &copy, turn.Owner)
	if err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	raw, err = s.product.ManagedServiceRequest(c, turn.Owner, inv.SessionID, "POST", "/turns/"+url.PathEscape(turn.ID)+"/"+kind, key, body)
	if err != nil {
		c.JSON(409, ErrorResponse{Error: err.Error()})
		return
	}
	var result any
	_ = json.Unmarshal(raw, &result)
	c.JSON(202, translateNativeValue(result, map[string]string{inv.SessionID: inv.EndpointID.String(), turn.ID: inv.ID.String()}))
}

// Native runtime IDs are private implementation details. The bridge translates
// both events and snapshots into the same public Session and Turn identities.
func (s *Server) nativeSessionMapping(ctx context.Context, v *sessionapi.Session, native string) (map[string]string, error) {
	mapping := map[string]string{native: v.ID.String()}
	for offset := 0; ; offset += 100 {
		turns, err := s.store.Endpoints().ListInvocations(ctx, store.EndpointInvocationFilter{EndpointID: v.ID, Limit: 100, Offset: offset})
		if err != nil {
			return nil, err
		}
		for _, inv := range turns {
			c, err := serviceapi.ReadContract(inv.Contract)
			if err != nil {
				return nil, err
			}
			turn, err := s.nativeServiceTurn(ctx, &c.Endpoint, inv)
			if err == nil {
				mapping[turn.ID] = inv.ID.String()
			} else if !errors.Is(err, store.ErrNotFound) {
				return nil, err
			}
		}
		if len(turns) < 100 {
			break
		}
	}
	for offset := 0; ; offset += 100 {
		rows, err := s.store.KV().Search(ctx, v.Tenant, sessionFilePath(v.ID), 100, offset)
		if err != nil {
			return nil, err
		}
		for _, row := range rows {
			var f publicSessionFile
			if err = json.Unmarshal(row.Value, &f); err != nil {
				return nil, err
			}
			if f.NativeID != "" {
				mapping[f.NativeID] = f.ID.String()
			}
		}
		if len(rows) < 100 {
			break
		}
	}
	return mapping, nil
}
func translateNativeValue(value any, mapping map[string]string) any {
	switch x := value.(type) {
	case map[string]any:
		for key, v := range x {
			if key == "request_id" {
				if id, ok := v.(string); ok {
					x[key] = "native:" + strings.TrimPrefix(id, "native:")
					continue
				}
			}
			x[key] = translateNativeValue(v, mapping)
		}
		return x
	case []any:
		for i := range x {
			x[i] = translateNativeValue(x[i], mapping)
		}
		return x
	case string:
		if id, ok := mapping[x]; ok {
			return id
		}
		if strings.HasPrefix(x, "/api/v1/agent-sessions/") {
			for from, to := range mapping {
				x = strings.ReplaceAll(x, "/"+from+"/", "/"+to+"/")
			}
		}
		return x
	default:
		return value
	}
}
func (s *Server) projectPublicManagedEvents(ctx context.Context, v *sessionapi.Session, c *serviceapi.Contract) error {
	conv, err := s.store.Endpoints().GetConversation(ctx, v.ID)
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	owner, native, err := s.publicNativeSession(ctx, v, c)
	if err != nil {
		return err
	}
	_ = conv
	mapping, err := s.nativeSessionMapping(ctx, v, native)
	if err != nil {
		return err
	}
	path := "service-session-native-cursors"
	after := ""
	if row, e := s.store.KV().Get(ctx, v.Tenant, path, v.ID.String()); e == nil {
		if e = json.Unmarshal(row.Value, &after); e != nil {
			return e
		}
	} else if !errors.Is(e, store.ErrNotFound) {
		return e
	}
	for {
		raw, e := s.product.ManagedServiceRequest(ctx, owner, native, "GET", "/events?limit=500&after="+url.QueryEscape(after), "", nil)
		if e != nil {
			return e
		}
		var page struct {
			Data []serviceapi.PublicEvent `json:"data"`
			Next string                   `json:"next_cursor"`
			More bool                     `json:"has_more"`
		}
		if e = json.Unmarshal(raw, &page); e != nil {
			return e
		}
		for _, event := range page.Data {
			// Public lifecycle is projected from the durable Turn record. Replaying
			// the native lifecycle too would regress an already completed Turn.
			if strings.HasPrefix(event.Type, "turn.") {
				continue
			}
			data := translateNativeValue(event.Data, mapping).(map[string]any)
			if _, e = s.sessionJournal(v).Append(ctx, "managed:"+event.ID, event.Type, data); e != nil {
				return e
			}
		}
		if page.Next == "" || page.More && page.Next == after {
			return store.ErrConflict
		}
		after = page.Next
		raw, _ = json.Marshal(after)
		if _, e = s.store.KV().Put(ctx, v.Tenant, path, v.ID.String(), raw); e != nil {
			return e
		}
		if !page.More {
			return nil
		}
	}
}
func (s *Server) managedPublicSnapshot(ctx context.Context, v *sessionapi.Session, c *serviceapi.Contract, asOf string) (map[string]any, error) {
	owner, native, err := s.publicNativeSession(ctx, v, c)
	if err != nil {
		return nil, err
	}
	raw, err := s.product.ManagedServiceRequest(ctx, owner, native, "GET", "/snapshot", "", nil)
	if err != nil {
		return nil, err
	}
	var snapshot map[string]any
	if err = json.Unmarshal(raw, &snapshot); err != nil {
		return nil, err
	}
	mapping, err := s.nativeSessionMapping(ctx, v, native)
	if err != nil {
		return nil, err
	}
	snapshot = translateNativeValue(snapshot, mapping).(map[string]any)
	snapshot["session"] = s.publicSessionView(ctx, v)
	snapshot["as_of"] = asOf
	return snapshot, nil
}

func (s *Server) forkPublicSession(c *gin.Context) {
	source, ok := s.loadPublicSession(c, "interact")
	if !ok {
		return
	}
	var req struct {
		Target     string `json:"target_session_id"`
		Checkpoint string `json:"checkpoint_id"`
		Reason     string `json:"reason"`
	}
	if c.ShouldBindJSON(&req) != nil || req.Target == source.ID.String() || req.Checkpoint == "" {
		c.JSON(400, ErrorResponse{Error: "a different target_session_id and checkpoint_id are required"})
		return
	}
	// Reuse the same ownership and scope checks for the destination before exposing
	// either native ID to the internal fork endpoint.
	saved := c.Params
	c.Params = append(gin.Params{}, saved...)
	for i := range c.Params {
		if c.Params[i].Key == "publicSessionId" {
			c.Params[i].Value = req.Target
		}
	}
	dest, ok := s.loadPublicSession(c, "interact")
	c.Params = saved
	if !ok {
		return
	}
	sc, err := serviceapi.ReadContract(source.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	dc, err := serviceapi.ReadContract(dest.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !s.publicSessionIsManaged(sc) || !s.publicSessionIsManaged(dc) {
		sessionUnsupported(c, "fork")
		return
	}
	if source.Status != "active" || dest.Status != "active" {
		c.JSON(409, ErrorResponse{Error: "source and destination Sessions must be active"})
		return
	}
	for _, session := range []*sessionapi.Session{source, dest} {
		active, e := s.store.Endpoints().CountActiveInvocations(c, store.EndpointInvocationFilter{EndpointID: session.ID})
		if e != nil {
			s.writeControlPlaneError(c, e)
			return
		}
		if active != 0 {
			c.JSON(409, ErrorResponse{Error: "fork requires idle source and destination Sessions"})
			return
		}
	}
	so, sn, err := s.publicNativeSession(c, source, sc)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	do, dn, err := s.publicNativeSession(c, dest, dc)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if so != do {
		c.JSON(409, ErrorResponse{Error: "fork requires Agent definitions with the same runtime owner"})
		return
	}
	raw, err := s.product.ManagedServiceRequest(c, so, sn, "POST", "/fork", c.GetHeader("Idempotency-Key"), gin.H{"target_session_id": dn, "checkpoint_id": req.Checkpoint, "reason": req.Reason})
	if err != nil {
		c.JSON(409, ErrorResponse{Error: err.Error()})
		return
	}
	var result any
	_ = json.Unmarshal(raw, &result)
	c.JSON(200, translateNativeValue(result, map[string]string{sn: source.ID.String(), dn: dest.ID.String()}))
}
