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
		AgentName:       meta.AgentName,
		InstanceID:      meta.InstanceId,
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
		s.server.connectHandler.HandleDisconnect(meta.Namespace, meta.InstanceId)
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
					logger.Error(err, "send failed", "instance", meta.InstanceId)
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
	s.server.distributor.PushFullSync(meta.Namespace, meta.AgentName, meta.InstanceId)

	// Phase 3: Recv loop — dispatch upstream messages.
	for {
		msg, err := stream.Recv()
		if err != nil {
			if err == io.EOF {
				logger.Info("stream closed by client", "instance", meta.InstanceId)
			} else {
				logger.Error(err, "recv error", "instance", meta.InstanceId)
				metrics.RecordStreamError(meta.Namespace, "upstream")
			}
			return nil
		}

		switch p := msg.Payload.(type) {
		case *Upstream_ConfigAck:
			s.handleConfigAck(meta, p.ConfigAck)
		case *Upstream_SessionReport:
			s.handleSessionReport(meta, p.SessionReport)
		case *Upstream_TeamEvent:
			s.handleTeamEvent(meta, p.TeamEvent)
		case *Upstream_EventReport:
			s.handleEventReport(meta, p.EventReport)
		case *Upstream_ContextReport:
			s.handleContextReport(meta, p.ContextReport)
		case *Upstream_Inventory:
			s.handleInventoryReport(meta, p.Inventory)
		case *Upstream_Heartbeat:
			if err := conn.Send(&Downstream{
				Payload: &Downstream_Heartbeat{Heartbeat: &Heartbeat{Timestamp: p.Heartbeat.Timestamp}},
			}); err != nil {
				logger.V(1).Info("heartbeat response failed", "instance", meta.InstanceId)
			}
		default:
			logger.Info("unknown upstream payload type", "instance", meta.InstanceId)
		}
	}
}

func (s *service) handleConfigAck(meta *UpstreamMeta, ack *ConfigAck) {
	logger := log.Log.WithName("asdp-service")
	if ack.Accepted {
		logger.Info("config ACK received",
			"instance", meta.InstanceId,
			"configType", ack.ConfigType,
			"version", ack.Version,
			"nonce", ack.Nonce,
		)
		metrics.RecordConfigPush(meta.Namespace, meta.AgentName, ack.ConfigType.String(), "ack")
	} else {
		logger.Info("config NACK received",
			"instance", meta.InstanceId,
			"configType", ack.ConfigType,
			"version", ack.Version,
			"nonce", ack.Nonce,
			"reason", ack.RejectReason,
		)
		metrics.RecordConfigPush(meta.Namespace, meta.AgentName, ack.ConfigType.String(), "nack")
		metrics.RecordConfigNack(meta.Namespace, meta.AgentName, ack.ConfigType.String())
	}
}

func (s *service) handleSessionReport(meta *UpstreamMeta, report *SessionReport) {
	if s.server.eventSink != nil {
		s.server.eventSink.HandleSessionReport(meta.Namespace, meta.AgentName, meta.InstanceId, report)
	}
}

func (s *service) handleTeamEvent(meta *UpstreamMeta, report *TeamEventReport) {
	if s.server.eventSink != nil {
		s.server.eventSink.HandleTeamEventReport(meta.Namespace, meta.AgentName, report)
	}
}

func (s *service) handleEventReport(meta *UpstreamMeta, report *EventReport) {
	if s.server.eventSink != nil {
		s.server.eventSink.HandleEventReport(meta.Namespace, meta.AgentName, meta.InstanceId, report)
	}
}

func (s *service) handleContextReport(meta *UpstreamMeta, report *ContextReport) {
	if s.server.eventSink != nil {
		s.server.eventSink.HandleContextReport(meta.Namespace, meta.AgentName, meta.InstanceId, report)
	}
}

func (s *service) handleInventoryReport(meta *UpstreamMeta, report *InventoryReport) {
	// The latest inventory is always kept in the connection registry for
	// REST/CLI queries; the sink gets a copy for logging/metrics.
	s.server.UpdateInventory(meta.Namespace, meta.InstanceId, report)
	if s.server.eventSink != nil {
		s.server.eventSink.HandleInventoryReport(meta.Namespace, meta.AgentName, meta.InstanceId, report)
	}
}
