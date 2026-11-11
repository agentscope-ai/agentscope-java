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
	"fmt"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

func (s *Server) sessionJournal(v *sessionapi.Session) serviceapi.Journal {
	return serviceapi.Journal{Store: s.store, Tenant: v.Tenant, InvocationID: v.ID}
}

// Copy committed Turn events into a durable Session log. Each receipt is advanced
// only after append, so retries and process restarts cannot skip or duplicate events.
func (s *Server) projectPublicSession(ctx context.Context, v *sessionapi.Session) error {
	return s.store.WithSessionLock(ctx, "session-projection:"+v.ID.String(), func(ctx context.Context) error {
		j := s.sessionJournal(v)
		contract, err := serviceapi.ReadContract(v.Contract)
		if err != nil {
			return err
		}
		managed := s.publicSessionIsManaged(contract)
		for offset := 0; ; offset += 100 {
			turns, err := s.store.Endpoints().ListInvocations(ctx, store.EndpointInvocationFilter{EndpointID: v.ID, Limit: 100, Offset: offset, OldestFirst: true})
			if err != nil {
				return err
			}
			for _, inv := range turns {
				if _, err = s.refreshServiceInvocation(ctx, inv.ID); err != nil {
					return err
				}
				if err = s.projectServiceInvocation(ctx, inv.ID); err != nil {
					return err
				}
				// Commit native resources before a public terminal Turn event, so a
				// completion notification never overtakes its messages or artifacts.
				if managed {
					if err = s.projectPublicManagedEvents(ctx, v, contract); err != nil {
						return err
					}
				}
				contract, err := serviceapi.ReadContract(inv.Contract)
				if err != nil {
					return err
				}
				path := "service-session-projection/" + v.ID.String()
				after := ""
				row, e := s.store.KV().Get(ctx, v.Tenant, path, inv.ID.String())
				if e == nil {
					if e = json.Unmarshal(row.Value, &after); e != nil {
						return e
					}
				} else if !errors.Is(e, store.ErrNotFound) {
					return e
				}
				for {
					events, next, more, e := serviceJournal(s.store, &contract.Endpoint, inv).Events(ctx, after, 500)
					if e != nil {
						return e
					}
					for _, event := range events {
						if managed && !strings.HasPrefix(event.Type, "invocation.") && !strings.HasPrefix(event.Type, "command.") {
							continue
						}
						data := map[string]any{}
						for k, value := range event.Data {
							data[k] = value
						}
						data["turn_id"] = inv.ID.String()
						if _, e = j.Append(ctx, inv.ID.String()+":"+event.ID, event.Type, data); e != nil {
							return e
						}
					}
					raw, _ := json.Marshal(next)
					if _, e = s.store.KV().Put(ctx, v.Tenant, path, inv.ID.String(), raw); e != nil {
						return e
					}
					after = next
					if !more {
						break
					}
				}
			}
			if len(turns) < 100 {
				if managed {
					return s.projectPublicManagedEvents(ctx, v, contract)
				}
				return nil
			}
		}
	})
}

func publicSessionValue(v *sessionapi.Session, value any) any {
	switch x := value.(type) {
	case map[string]any:
		out := map[string]any{}
		for k, val := range x {
			switch k {
			case "endpoint_id", "release_id", "conversation_id", "runtime_session_id":
				continue
			case "invocation_id":
				k = "turn_id"
			}
			out[k] = publicSessionValue(v, val)
		}
		return out
	case []any:
		for i := range x {
			x[i] = publicSessionValue(v, x[i])
		}
		return x
	case string:
		return strings.ReplaceAll(x, "/invoke/v1/invocations/", "/api/v1/agent-sessions/"+v.ID.String()+"/turns/")
	default:
		return value
	}
}
func publicSessionJSON(v *sessionapi.Session, value any) any {
	raw, _ := json.Marshal(value)
	var out any
	_ = json.Unmarshal(raw, &out)
	return publicSessionValue(v, out)
}
func publicEventView(v *sessionapi.Session, event *serviceapi.PublicEvent) gin.H {
	typ := strings.Replace(event.Type, "invocation.", "turn.", 1)
	turn := event.Data["turn_id"]
	if turn == nil && event.InvocationID != v.ID.String() {
		turn = event.InvocationID
	}
	data := publicSessionJSON(v, event.Data).(map[string]any)
	if turn != nil {
		data["turn_id"] = turn
	}
	if strings.HasPrefix(typ, "turn.") {
		if status, ok := data["status"].(string); ok {
			data["status"] = publicTurnStatus(model.EndpointInvocationStatus(status))
		}
	}
	return gin.H{"schema_version": event.SchemaVersion, "id": event.ID, "type": typ, "session_id": v.ID, "turn_id": turn, "created_at": event.CreatedAt, "cursor": event.Cursor, "data": data}
}
func sessionSnapshotView(v *sessionapi.Session, snapshot serviceapi.Snapshot) gin.H {
	return gin.H{"session": v.View(), "as_of": snapshot.AsOf, "items": publicSessionJSON(v, snapshot.Items), "tools": publicSessionJSON(v, snapshot.Tools), "required_actions": publicSessionJSON(v, snapshot.Actions), "steps": publicSessionJSON(v, snapshot.Steps), "artifacts": publicSessionJSON(v, snapshot.Artifacts), "usage": publicSessionJSON(v, snapshot.Usage)}
}
func (s *Server) publicSessionSnapshot(c *gin.Context) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	if err := s.projectPublicSession(c, v); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	snapshot, err := s.sessionJournal(v).Snapshot(c)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	contract, err := serviceapi.ReadContract(v.Contract)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if s.publicSessionIsManaged(contract) {
		result, e := s.managedPublicSnapshot(c, v, contract, snapshot.AsOf)
		if e != nil {
			s.writeControlPlaneError(c, e)
			return
		}
		c.JSON(200, result)
		return
	}
	c.JSON(200, sessionSnapshotView(v, snapshot))
}
func (s *Server) publicTurnSnapshot(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "read")
	if !ok {
		return
	}
	v := c.MustGet(publicSessionContextKey).(*sessionapi.Session)
	inv, err := s.refreshServiceInvocation(c, inv.ID)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err := s.projectServiceInvocation(c, inv.ID); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	snapshot, err := serviceJournal(s.store, ep, inv).Snapshot(c)
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	view := sessionSnapshotView(v, snapshot)
	view["turn"] = s.publicTurnView(v, inv)
	switch {
	case strings.HasSuffix(c.FullPath(), "/actions"):
		c.JSON(200, gin.H{"required_actions": view["required_actions"], "as_of": snapshot.AsOf})
	case strings.HasSuffix(c.FullPath(), "/usage"):
		c.JSON(200, gin.H{"usage": view["usage"], "as_of": snapshot.AsOf})
	case strings.HasSuffix(c.FullPath(), "/artifacts"):
		c.JSON(200, gin.H{"artifacts": view["artifacts"], "as_of": snapshot.AsOf})
	default:
		c.JSON(200, view)
	}
}
func (s *Server) publicSessionEvents(c *gin.Context) { s.servePublicSessionEvents(c, false) }
func (s *Server) publicSessionStream(c *gin.Context) { s.servePublicSessionEvents(c, true) }
func (s *Server) servePublicSessionEvents(c *gin.Context, stream bool) {
	v, ok := s.loadPublicSession(c, "read")
	if !ok {
		return
	}
	after := serviceAfter(c)
	if _, err := serviceapi.Position(v.ID, after); err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid Session cursor"})
		return
	}
	limit := queryInt(c, "limit", 200)
	if limit < 1 || limit > 1000 {
		c.JSON(400, ErrorResponse{Error: "limit must be 1..1000"})
		return
	}
	if err := s.projectPublicSession(c, v); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	j := s.sessionJournal(v)
	events, next, more, err := j.Events(c, after, limit)
	if errors.Is(err, serviceapi.ErrCursorExpired) {
		c.JSON(410, gin.H{"error": "cursor_expired", "snapshot_url": "/api/v1/agent-sessions/" + v.ID.String() + "/snapshot"})
		return
	}
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if !stream {
		data := []gin.H{}
		for _, e := range events {
			data = append(data, publicEventView(v, e))
		}
		c.JSON(200, gin.H{"data": data, "next_cursor": next, "has_more": more})
		return
	}
	prepareEventStream(c)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	heartbeat := time.Now()
	for {
		for _, e := range events {
			raw, _ := json.Marshal(publicEventView(v, e))
			if _, err = fmt.Fprintf(c.Writer, "id: %s\nevent: %s\ndata: %s\n\n", e.Cursor, strings.Replace(e.Type, "invocation.", "turn.", 1), raw); err != nil {
				return
			}
		}
		c.Writer.Flush()
		after = next
		if !more {
			select {
			case <-c.Request.Context().Done():
				return
			case <-ticker.C:
			}
		}
		if err = s.projectPublicSession(c, v); err != nil {
			return
		}
		events, next, more, err = j.Events(c, after, limit)
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
