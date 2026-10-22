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

package asdp

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/version"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

// HTTP uses the same fenced commands and reports as ASDP, with a durable
// mailbox instead of an instance-local gRPC connection. Requests may reach any
// control-plane replica. Commands remain queued until explicitly acknowledged.
type httpPresence struct {
	Meta   json.RawMessage `json:"meta"`
	SeenAt time.Time       `json:"seen_at"`
}
type httpCommand struct {
	ID      string          `json:"id"`
	Message json.RawMessage `json:"message"`
}
type rejectedReport struct {
	Index int    `json:"index"`
	Error string `json:"error"`
}

type httpExchange struct {
	Meta        json.RawMessage   `json:"meta"`
	Connect     json.RawMessage   `json:"connect"`
	Messages    []json.RawMessage `json:"messages"`
	Ack         []string          `json:"ack"`
	WaitSeconds int               `json:"wait_seconds"`
}

func (s *Server) ConfigureHTTP(st store.Store) { s.httpStore = st }
func (s *Server) HTTPHandler() http.Handler    { return http.HandlerFunc(s.exchangeHTTP) }
func httpIdentityKey(namespace, agent, instance string) string {
	digest := sha256.Sum256([]byte(namespace + "\x00" + agent + "\x00" + instance))
	return hex.EncodeToString(digest[:])
}
func httpMailbox(key string, generation int64) string {
	return fmt.Sprintf("service-runtime/commands/%s/%d", key, generation)
}
func (s *Server) exchangeHTTP(w http.ResponseWriter, r *http.Request) {
	fail := func(status int, err error) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
	}
	if r.Method != http.MethodPost {
		fail(405, fmt.Errorf("POST required"))
		return
	}
	if s.httpStore == nil || s.identityValidator == nil || s.eventSink == nil {
		fail(503, fmt.Errorf("runtime transport unavailable"))
		return
	}
	var req httpExchange
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&req); err != nil {
		fail(400, err)
		return
	}
	if len(req.Messages) > 256 || len(req.Ack) > 256 || req.WaitSeconds < 0 || req.WaitSeconds > 20 {
		fail(400, fmt.Errorf("messages/ack limit is 256; wait_seconds must be 0..20"))
		return
	}
	meta := new(UpstreamMeta)
	connect := new(ConnectRequest)
	if err := protojson.Unmarshal(req.Meta, meta); err != nil {
		fail(400, err)
		return
	}
	if err := protojson.Unmarshal(req.Connect, connect); err != nil {
		fail(400, err)
		return
	}
	if meta.GetTenant() == "" || meta.GetNamespace() == "" || meta.GetAgentId() == "" || meta.GetBindingId() == "" || meta.GetAgentKey() == "" || meta.GetInstanceKey() == "" || meta.GetGeneration() <= 0 {
		fail(400, fmt.Errorf("complete registered runtime identity is required"))
		return
	}
	credential := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
	if err := s.identityValidator(r.Context(), meta, credential, false); err != nil {
		fail(401, fmt.Errorf("identity claim rejected"))
		return
	}
	messages := make([]*Upstream, 0, len(req.Messages))
	for _, raw := range req.Messages {
		msg := new(Upstream)
		if err := protojson.Unmarshal(raw, msg); err != nil {
			fail(400, err)
			return
		}
		if msg.Meta != nil && !proto.Equal(msg.Meta, meta) {
			fail(400, fmt.Errorf("message identity differs from envelope"))
			return
		}
		switch msg.Payload.(type) {
		case *Upstream_ConfigAck, *Upstream_SessionReport, *Upstream_ExecutionAttempt, *Upstream_EventReport, *Upstream_ContextReport, *Upstream_Inventory, *Upstream_ConversationTurn, *Upstream_Heartbeat:
		default:
			fail(400, fmt.Errorf("unsupported upstream payload"))
			return
		}
		messages = append(messages, msg)
	}
	key := httpIdentityKey(meta.Namespace, meta.AgentId, meta.InstanceKey)
	mailbox := httpMailbox(key, meta.Generation)
	for _, id := range req.Ack {
		if len(id) != 64 {
			fail(400, fmt.Errorf("invalid command acknowledgement"))
			return
		}
		if _, err := hex.DecodeString(id); err != nil {
			fail(400, err)
			return
		}
	}
	metaJSON, _ := protojson.Marshal(meta)
	presence, _ := json.Marshal(httpPresence{Meta: metaJSON, SeenAt: time.Now().UTC()})
	if err := s.storeHTTPPresence(r.Context(), meta, key, presence); err != nil {
		if errors.Is(err, store.ErrForbidden) {
			fail(401, fmt.Errorf("runtime generation superseded"))
		} else {
			fail(503, err)
		}
		return
	}
	s.eventSink.HandleConnect(meta.Tenant, meta.Namespace, meta.AgentId, meta.BindingId, meta.AgentKey, meta.InstanceKey, meta.Generation, connect.Runtime, connect.SdkVersion, connect.Capabilities)
	for _, id := range req.Ack {
		if err := s.httpStore.KV().Delete(r.Context(), meta.Tenant, mailbox, id); err != nil && !errors.Is(err, store.ErrNotFound) {
			fail(503, err)
			return
		}
	}
	response := struct {
		Messages        []json.RawMessage `json:"messages"`
		Commands        []httpCommand     `json:"commands"`
		RejectedReports []rejectedReport  `json:"rejected_reports"`
	}{Messages: []json.RawMessage{}, Commands: []httpCommand{}, RejectedReports: []rejectedReport{}}
	add := func(msg *Downstream) {
		raw, _ := protojson.Marshal(msg)
		response.Messages = append(response.Messages, raw)
	}
	add(&Downstream{Payload: &Downstream_ConnectAck{ConnectAck: &ConnectResponse{Accepted: true, ControlPlaneVersion: version.Version}}})
	handler := &service{server: s}
	for index, msg := range messages {
		var reportErr error
		switch p := msg.Payload.(type) {
		case *Upstream_ConfigAck:
			handler.handleConfigAck(meta, p.ConfigAck)
		case *Upstream_SessionReport:
			handler.handleSessionReport(meta, p.SessionReport)
		case *Upstream_ExecutionAttempt:
			reportErr = handler.handleExecutionAttempt(meta, p.ExecutionAttempt)
		case *Upstream_EventReport:
			add(&Downstream{Payload: &Downstream_EventAck{EventAck: handler.handleEventReport(meta, p.EventReport)}})
		case *Upstream_ContextReport:
			handler.handleContextReport(meta, p.ContextReport)
		case *Upstream_Inventory:
			handler.handleInventoryReport(meta, p.Inventory)
		case *Upstream_ConversationTurn:
			reportErr = handler.handleConversationTurnReport(meta, p.ConversationTurn)
		case *Upstream_Heartbeat:
			add(&Downstream{Payload: &Downstream_Heartbeat{Heartbeat: p.Heartbeat}})
		}
		if reportErr != nil {
			if errors.Is(reportErr, store.ErrNotFound) || errors.Is(reportErr, store.ErrForbidden) {
				response.RejectedReports = append(response.RejectedReports, rejectedReport{Index: index, Error: reportErr.Error()})
			} else {
				fail(503, reportErr)
				return
			}
		}

	}
	deadline := time.Now().Add(time.Duration(req.WaitSeconds) * time.Second)
	for {
		rows, err := s.httpStore.KV().Search(r.Context(), meta.Tenant, mailbox, 100, 0)
		if err != nil {
			fail(503, err)
			return
		}
		for _, row := range rows {
			response.Commands = append(response.Commands, httpCommand{ID: row.Key, Message: row.Value})
		}
		if len(response.Commands) > 0 || len(req.Messages) > 0 || !time.Now().Before(deadline) {
			break
		}
		select {
		case <-r.Context().Done():
			return
		case <-time.After(250 * time.Millisecond):
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(response)
}
func (s *Server) sendRuntime(tenant, namespace, agentID, instanceID string, down *Downstream) error {
	conn, connected := s.GetConnectionForAgentInstance(tenant, namespace, agentID, instanceID)
	if s.httpStore == nil {
		if connected {
			return conn.Send(down)
		}
		return ErrInstanceNotConnected
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	key := httpIdentityKey(namespace, agentID, instanceID)
	row, err := s.httpStore.KV().Get(ctx, tenant, "service-runtime/presence", key)
	if errors.Is(err, store.ErrNotFound) {
		if connected {
			return conn.Send(down)
		}
		return ErrInstanceNotConnected
	}
	if err != nil {
		return err
	}
	var presence httpPresence
	if err = json.Unmarshal(row.Value, &presence); err != nil {
		return err
	}
	meta := new(UpstreamMeta)
	if err = protojson.Unmarshal(presence.Meta, meta); err != nil {
		return err
	}
	// Never send to an old local stream after a newer HTTP generation has registered.
	if connected && conn.Generation >= meta.Generation {
		return conn.Send(down)
	}
	if time.Since(presence.SeenAt) > 90*time.Second {
		return ErrInstanceNotConnected
	}
	// The attempt's instance fence remains part of the command payload. A new
	// registration gets a new mailbox and cannot acknowledge old-generation work.
	raw, err := protojson.Marshal(down)
	if err != nil {
		return err
	}
	identity := proto.Clone(down).(*Downstream)
	if cmd := identity.GetExecutionAttempt(); cmd != nil {
		cmd.Timestamp = 0
	}
	stable, _ := protojson.Marshal(identity)
	digest := sha256.Sum256(stable)
	_, _, err = s.httpStore.KV().PutIfVersion(ctx, tenant, httpMailbox(key, meta.Generation), hex.EncodeToString(digest[:]), raw, 0)
	return err
}

// A request can pass validation immediately before a replacement registers.
// CAS prevents that delayed old request from routing later commands back to its mailbox.
func (s *Server) storeHTTPPresence(ctx context.Context, meta *UpstreamMeta, key string, raw json.RawMessage) error {
	for {
		version := int64(0)
		row, err := s.httpStore.KV().Get(ctx, meta.Tenant, "service-runtime/presence", key)
		if err == nil {
			version = row.Version
			var current httpPresence
			if err = json.Unmarshal(row.Value, &current); err != nil {
				return err
			}
			previous := new(UpstreamMeta)
			if err = protojson.Unmarshal(current.Meta, previous); err != nil {
				return err
			}
			if previous.Generation > meta.Generation {
				return store.ErrForbidden
			}
		} else if !errors.Is(err, store.ErrNotFound) {
			return err
		}
		_, written, err := s.httpStore.KV().PutIfVersion(ctx, meta.Tenant, "service-runtime/presence", key, raw, version)
		if err != nil {
			return err
		}
		if written {
			return nil
		}
	}
}
