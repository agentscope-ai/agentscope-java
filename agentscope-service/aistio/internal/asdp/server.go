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
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/keepalive"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/metrics"
)

// Connection represents a connected data plane instance with its gRPC stream.
type Connection struct {
	AgentName       string
	InstanceID      string
	Namespace       string
	Runtime         string
	SDKVersion      string
	Capabilities    []string
	SessionAffinity string

	sendCh chan *Downstream
	cancel context.CancelFunc
}

// Send enqueues a Downstream message to the connection's write goroutine.
// If the send buffer is full the consumer is too slow or stuck; rather than
// silently dropping config (which would leave the data plane permanently stale),
// the connection is torn down so the instance reconnects and receives a full
// sync. Returns an error in that case.
func (c *Connection) Send(msg *Downstream) error {
	select {
	case c.sendCh <- msg:
		return nil
	default:
		c.Close()
		return fmt.Errorf("send channel full for instance %s; closing connection", c.InstanceID)
	}
}

// Close cancels the connection context, which triggers stream cleanup.
func (c *Connection) Close() {
	if c.cancel != nil {
		c.cancel()
	}
}

const sendChSize = 64

// Server implements the ASDP gRPC server that manages data plane connections.
type Server struct {
	mu          sync.RWMutex
	connections map[string]*Connection // key: namespace/instanceID
	inventory   map[string]*InstanceInventory
	grpcServer  *grpc.Server
	addr        string

	connectHandler *ConnectHandler
	distributor    *Distributor

	// EventSink receives upstream events (SessionReport, TeamEvent) for processing
	// by controllers. Set after construction via SetEventSink.
	eventSink EventSink
}

// EventSink processes upstream events from data plane instances.
type EventSink interface {
	HandleSessionReport(namespace, agentName, instanceID string, report *SessionReport)
	HandleTeamEventReport(namespace, agentName string, report *TeamEventReport)
	// HandleEventReport processes a Level-2 event stream batch (session_events).
	HandleEventReport(namespace, agentName, instanceID string, report *EventReport)
	// HandleContextReport processes a Level-4 effective-context report (context_snapshots).
	HandleContextReport(namespace, agentName, instanceID string, report *ContextReport)
	// HandleInventoryReport processes an instance inventory report. The latest
	// report is also kept in the server connection registry (see GetInventory*).
	HandleInventoryReport(namespace, agentName, instanceID string, report *InventoryReport)
}

// InstanceInventory couples the latest InventoryReport from a connected
// instance with the time it was received.
type InstanceInventory struct {
	Namespace  string
	AgentName  string
	InstanceID string
	Report     *InventoryReport
	UpdatedAt  time.Time
}

// ServerConfig holds configuration for the ASDP gRPC server.
type ServerConfig struct {
	Addr      string
	TLSCert   string
	TLSKey    string
	TLSCACert string
}

// NewServer creates a new ASDP gRPC server with optional mTLS and keepalive.
// It returns an error (instead of panicking) when TLS material cannot be loaded,
// so the caller can decide whether the failure is fatal.
func NewServer(cfg ServerConfig) (*Server, error) {
	var opts []grpc.ServerOption

	// mTLS / TLS configuration.
	if cfg.TLSCert != "" && cfg.TLSKey != "" {
		cert, err := tls.LoadX509KeyPair(cfg.TLSCert, cfg.TLSKey)
		if err != nil {
			return nil, fmt.Errorf("failed to load TLS cert/key: %w", err)
		}
		tlsConfig := &tls.Config{
			Certificates: []tls.Certificate{cert},
			ClientAuth:   tls.NoClientCert,
			MinVersion:   tls.VersionTLS12,
		}
		if cfg.TLSCACert != "" {
			caCert, err := os.ReadFile(cfg.TLSCACert)
			if err != nil {
				return nil, fmt.Errorf("failed to read CA cert: %w", err)
			}
			pool := x509.NewCertPool()
			if !pool.AppendCertsFromPEM(caCert) {
				return nil, fmt.Errorf("failed to parse CA cert %s", cfg.TLSCACert)
			}
			tlsConfig.ClientAuth = tls.RequireAndVerifyClientCert
			tlsConfig.ClientCAs = pool
		}
		opts = append(opts, grpc.Creds(credentials.NewTLS(tlsConfig)))
	}

	// Keepalive parameters for connection health and idle management.
	opts = append(opts,
		grpc.KeepaliveParams(keepalive.ServerParameters{
			MaxConnectionIdle:     5 * time.Minute,
			MaxConnectionAge:      30 * time.Minute,
			MaxConnectionAgeGrace: 10 * time.Second,
			Time:                  30 * time.Second,
			Timeout:               10 * time.Second,
		}),
		grpc.KeepaliveEnforcementPolicy(keepalive.EnforcementPolicy{
			MinTime:             10 * time.Second,
			PermitWithoutStream: true,
		}),
	)

	s := &Server{
		connections: make(map[string]*Connection),
		inventory:   make(map[string]*InstanceInventory),
		grpcServer:  grpc.NewServer(opts...),
		addr:        cfg.Addr,
	}
	s.connectHandler = NewConnectHandler(s)

	snapshots := NewSnapshotStore()
	s.distributor = NewDistributor(s, snapshots)

	RegisterAgentDataPlaneServiceServer(s.grpcServer, &service{server: s})
	return s, nil
}

// SetEventSink sets the handler for upstream events.
func (s *Server) SetEventSink(sink EventSink) {
	s.eventSink = sink
}

// Distributor returns the server's config distributor.
func (s *Server) Distributor() *Distributor {
	return s.distributor
}

// Start begins listening for gRPC connections.
func (s *Server) Start() error {
	logger := log.Log.WithName("asdp-server")

	lis, err := net.Listen("tcp", s.addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", s.addr, err)
	}

	logger.Info("ASDP gRPC server starting", "addr", s.addr)
	return s.grpcServer.Serve(lis)
}

// Stop drains all active connections and gracefully stops the gRPC server.
func (s *Server) Stop() {
	s.mu.Lock()
	for _, conn := range s.connections {
		conn.Close()
	}
	s.connections = make(map[string]*Connection)
	s.inventory = make(map[string]*InstanceInventory)
	s.mu.Unlock()

	s.grpcServer.GracefulStop()
}

// RegisterConnection registers a new data plane connection after handshake.
func (s *Server) RegisterConnection(conn *Connection) {
	s.mu.Lock()
	defer s.mu.Unlock()

	key := GetInstanceKey(conn.Namespace, conn.InstanceID)
	s.connections[key] = conn
	metrics.RecordGRPCConnection(1)

	logger := log.Log.WithName("asdp")
	logger.Info("data plane connected",
		"agent", conn.AgentName,
		"instance", conn.InstanceID,
		"runtime", conn.Runtime,
		"capabilities", conn.Capabilities,
	)
}

// UnregisterConnection removes a data plane connection.
func (s *Server) UnregisterConnection(namespace, instanceID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	key := GetInstanceKey(namespace, instanceID)
	if conn, ok := s.connections[key]; ok {
		conn.Close()
		delete(s.connections, key)
		metrics.RecordGRPCConnection(-1)
	}
	delete(s.inventory, key)

	logger := log.Log.WithName("asdp")
	logger.Info("data plane disconnected", "instance", instanceID, "namespace", namespace)
}

// GetConnection retrieves a connection by instance key.
func (s *Server) GetConnection(namespace, instanceID string) (*Connection, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	key := GetInstanceKey(namespace, instanceID)
	conn, ok := s.connections[key]
	return conn, ok
}

// GetConnectionsForAgent returns all connections for a given agent.
func (s *Server) GetConnectionsForAgent(namespace, agentName string) []*Connection {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var conns []*Connection
	for _, conn := range s.connections {
		if conn.Namespace == namespace && conn.AgentName == agentName {
			conns = append(conns, conn)
		}
	}
	return conns
}

// ListConnections returns all active connections.
func (s *Server) ListConnections() []*Connection {
	s.mu.RLock()
	defer s.mu.RUnlock()

	conns := make([]*Connection, 0, len(s.connections))
	for _, conn := range s.connections {
		conns = append(conns, conn)
	}
	return conns
}

// ConnectionCount returns the number of active connections.
func (s *Server) ConnectionCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.connections)
}

// UpdateInventory records the latest inventory report for a connected instance.
func (s *Server) UpdateInventory(namespace, instanceID string, report *InventoryReport) {
	s.mu.Lock()
	defer s.mu.Unlock()

	key := GetInstanceKey(namespace, instanceID)
	conn, ok := s.connections[key]
	if !ok {
		return
	}
	s.inventory[key] = &InstanceInventory{
		Namespace:  namespace,
		AgentName:  conn.AgentName,
		InstanceID: instanceID,
		Report:     report,
		UpdatedAt:  time.Now().UTC(),
	}
}

// GetInventory returns the latest inventory for a specific instance.
func (s *Server) GetInventory(namespace, instanceID string) (*InstanceInventory, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	inv, ok := s.inventory[GetInstanceKey(namespace, instanceID)]
	return inv, ok
}

// GetInventoriesForAgent returns the latest inventories of every connected
// instance of the given agent.
func (s *Server) GetInventoriesForAgent(namespace, agentName string) []*InstanceInventory {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var out []*InstanceInventory
	for _, inv := range s.inventory {
		if inv.Namespace == namespace && inv.AgentName == agentName {
			out = append(out, inv)
		}
	}
	return out
}
