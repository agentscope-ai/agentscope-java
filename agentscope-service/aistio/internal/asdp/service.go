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

package asdp

import (
	"context"
	"io"
	"strings"

	"google.golang.org/grpc/metadata"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/metrics"
)

// service implements the AgentDataPlaneServiceServer gRPC interface.
type service struct {
	UnimplementedAgentDataPlaneServiceServer
	server *Server
}

// Connect handles a bidirectional stream from a data plane instance.
func (s *service) Connect(stream AgentDataPlaneService_ConnectServer) error {
	logger := log.Log.WithName("asdp-service")

	// Phase 1: Wait for the handshake (first Upstream must be ConnectRequest).
	firstMsg, err := stream.Recv()
	if err != nil {
		return err
	}
	connReq := firstMsg.GetConnect()
	if connReq == nil {
		return stream.Send(&Downstream{
			Payload: &Downstream_ConnectAck{
				ConnectAck: &ConnectResponse{
					Accepted:     false,
					RejectReason: "first message must be ConnectRequest",
				},
			},
		})
	}

	meta := firstMsg.Meta
	if meta == nil {
		return stream.Send(&Downstream{
			Payload: &Downstream_ConnectAck{
				ConnectAck: &ConnectResponse{
					Accepted:     false,
					RejectReason: "UpstreamMeta is required",
				},
			},
		})
	}
	credential := streamCredential(stream.Context())
	trustedWorkloadIdentity := s.server.authToken != "" && credential == s.server.authToken
	if s.server.identityValidator != nil {
		if err := s.server.identityValidator(stream.Context(), meta, credential, trustedWorkloadIdentity); err != nil {
			return stream.Send(&Downstream{Payload: &Downstream_ConnectAck{ConnectAck: &ConnectResponse{
				Accepted: false, RejectReason: "identity claim rejected",
			}}})
		}
	} else if !authorizedStream(stream.Context(), s.server.authToken) {
		return stream.Send(&Downstream{Payload: &Downstream_ConnectAck{ConnectAck: &ConnectResponse{
			Accepted: false, RejectReason: "unauthorized",
		}}})
	}

	resp := s.server.connectHandler.HandleConnect(stream.Context(), meta, connReq)
	if err := stream.Send(&Downstream{
		Payload: &Downstream_ConnectAck{ConnectAck: resp},
	}); err != nil {
		return err
	}
	if !resp.Accepted {
		return nil
	}

	// Phase 2: Set up the connection with a writer goroutine.
	ctx, cancel := context.WithCancel(stream.Context())
	conn := &Connection{
		Tenant:          meta.GetTenant(),
		AgentID:         meta.AgentId,
		BindingID:       meta.BindingId,
		AgentName:       meta.AgentKey,
		InstanceID:      meta.InstanceKey,
		Generation:      meta.Generation,
		Namespace:       meta.Namespace,
		Runtime:         connReq.Runtime,
		SDKVersion:      connReq.SdkVersion,
		Capabilities:    connReq.Capabilities,
		SessionAffinity: connReq.SessionAffinity,
		sendCh:          make(chan *Downstream, sendChSize),
		cancel:          cancel,
	}
	s.server.RegisterConnection(conn)
	defer func() {
		s.server.connectHandler.HandleDisconnect(meta.GetTenant(), meta.Namespace, meta.AgentId, meta.InstanceKey)
		cancel()
	}()

	// Writer goroutine: drains sendCh and writes to stream.
	// gRPC stream Send is NOT concurrency-safe, so a single goroutine owns writes.
	go func() {
		for {
			select {
			case msg, ok := <-conn.sendCh:
				if !ok {
					return
				}
				if err := stream.Send(msg); err != nil {
					logger.Error(err, "send failed", "instance", meta.InstanceKey)
					metrics.RecordStreamError(meta.Namespace, "downstream")
					cancel()
					return
				}
			case <-ctx.Done():
				return
			}
		}
	}()

	// Push full config sync after handshake.
	s.server.distributor.PushFullSync(meta.GetTenant(), meta.Namespace, meta.AgentId, meta.AgentKey, meta.InstanceKey)

	// Phase 3: Recv loop — dispatch upstream messages.
	for {
		msg, err := stream.Recv()
		if err != nil {
			if err == io.EOF {
				logger.Info("stream closed by client", "instance", meta.InstanceKey)
			} else {
				logger.Error(err, "recv error", "instance", meta.InstanceKey)
				metrics.RecordStreamError(meta.Namespace, "upstream")
			}
			return nil
		}

		switch p := msg.Payload.(type) {
		case *Upstream_ConfigAck:
			s.handleConfigAck(meta, p.ConfigAck)
		case *Upstream_SessionReport:
			s.handleSessionReport(meta, p.SessionReport)
		case *Upstream_ExecutionAttempt:
			s.handleExecutionAttempt(meta, p.ExecutionAttempt)
		case *Upstream_EventReport:
			ack := s.handleEventReport(meta, p.EventReport)
			if err := conn.Send(&Downstream{Payload: &Downstream_EventAck{EventAck: ack}}); err != nil {
				logger.V(1).Info("event acknowledgement failed", "instance", meta.InstanceKey, "reportId", ack.GetReportId())
			}
		case *Upstream_ContextReport:
			s.handleContextReport(meta, p.ContextReport)
		case *Upstream_Inventory:
			s.handleInventoryReport(meta, p.Inventory)
		case *Upstream_ConversationTurn:
			s.handleConversationTurnReport(meta, p.ConversationTurn)
		case *Upstream_Heartbeat:
			if err := conn.Send(&Downstream{
				Payload: &Downstream_Heartbeat{Heartbeat: &Heartbeat{Timestamp: p.Heartbeat.Timestamp}},
			}); err != nil {
				logger.V(1).Info("heartbeat response failed", "instance", meta.InstanceKey)
			}
		default:
			logger.Info("unknown upstream payload type", "instance", meta.InstanceKey)
		}
	}
}

func authorizedStream(ctx context.Context, expected string) bool {
	if expected == "" {
		return true
	}
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return false
	}
	for _, value := range append(md.Get("authorization"), md.Get("x-builder-internal-token")...) {
		if strings.TrimPrefix(value, "Bearer ") == expected {
			return true
		}
	}
	return false
}

func streamCredential(ctx context.Context) string {
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return ""
	}
	for _, value := range append(md.Get("authorization"), md.Get("x-builder-internal-token")...) {
		if credential := strings.TrimSpace(strings.TrimPrefix(value, "Bearer ")); credential != "" {
			return credential
		}
	}
	return ""
}

func (s *service) handleConfigAck(meta *UpstreamMeta, ack *ConfigAck) {
	logger := log.Log.WithName("asdp-service")
	if ack.Accepted {
		logger.Info("config ACK received",
			"instance", meta.InstanceKey,
			"configType", ack.ConfigType,
			"version", ack.Version,
			"nonce", ack.Nonce,
		)
		metrics.RecordConfigPush(meta.Namespace, meta.AgentId, ack.ConfigType.String(), "ack")
	} else {
		logger.Info("config NACK received",
			"instance", meta.InstanceKey,
			"configType", ack.ConfigType,
			"version", ack.Version,
			"nonce", ack.Nonce,
			"reason", ack.RejectReason,
		)
		metrics.RecordConfigPush(meta.Namespace, meta.AgentId, ack.ConfigType.String(), "nack")
		metrics.RecordConfigNack(meta.Namespace, meta.AgentId, ack.ConfigType.String())
	}
}

func (s *service) handleSessionReport(meta *UpstreamMeta, report *SessionReport) {
	if s.server.eventSink != nil {
		s.server.eventSink.HandleSessionReport(reportIdentity(meta), report)
	}
}

func (s *service) handleExecutionAttempt(meta *UpstreamMeta, report *ExecutionAttemptReport) {
	if s.server.eventSink != nil {
		s.server.eventSink.HandleExecutionAttemptReport(meta.GetTenant(), meta.Namespace, meta.AgentId, meta.BindingId,
			meta.InstanceKey, meta.Generation, report)
	}
}

func (s *service) handleConversationTurnReport(meta *UpstreamMeta, report *ConversationTurnReport) {
	if s.server.eventSink != nil {
		s.server.eventSink.HandleConversationTurnReport(reportIdentity(meta), report)
	}
}

func (s *service) handleEventReport(meta *UpstreamMeta, report *EventReport) *EventReportAck {
	if s.server.eventSink != nil {
		return s.server.eventSink.HandleEventReport(reportIdentity(meta), report)
	}
	return &EventReportAck{ReportId: report.GetReportId(), Error: "event sink unavailable"}
}

func (s *service) handleContextReport(meta *UpstreamMeta, report *ContextReport) {
	if s.server.eventSink != nil {
		s.server.eventSink.HandleContextReport(reportIdentity(meta), report)
	}
}

func (s *service) handleInventoryReport(meta *UpstreamMeta, report *InventoryReport) {
	// The latest inventory is always kept in the connection registry for
	// REST/CLI queries; the sink gets a copy for logging/metrics.
	s.server.UpdateInventory(meta.GetTenant(), meta.Namespace, meta.AgentId, meta.InstanceKey, report)
	if s.server.eventSink != nil {
		s.server.eventSink.HandleInventoryReport(reportIdentity(meta), report)
	}
}

func reportIdentity(meta *UpstreamMeta) ReportIdentity {
	return ReportIdentity{
		Tenant:             meta.GetTenant(),
		Namespace:          meta.GetNamespace(),
		AgentID:            meta.GetAgentId(),
		BindingID:          meta.GetBindingId(),
		AgentKey:           meta.GetAgentKey(),
		InstanceKey:        meta.GetInstanceKey(),
		InstanceGeneration: meta.GetGeneration(),
	}
}
