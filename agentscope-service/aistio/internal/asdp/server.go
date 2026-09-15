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
	Tenant          string
	AgentID         string
	BindingID       string
	AgentName       string
	InstanceID      string
	Generation      int64
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
	authToken   string

	connectHandler *ConnectHandler
	distributor    *Distributor

	// EventSink receives upstream reports for processing
	// by controllers. Set after construction via SetEventSink.
	eventSink         EventSink
	identityValidator IdentityValidator
}

// IdentityValidator authenticates the stable Catalog identity carried by an
// ASDP stream. trustedWorkloadIdentity is true only for the configured internal
// workload token; external applications must present their registration token.
type IdentityValidator func(ctx context.Context, meta *UpstreamMeta, credential string, trustedWorkloadIdentity bool) error

// ReportIdentity is the authenticated runtime identity attached to every
// report. Report payloads never get to select their logical Agent identity.
type ReportIdentity struct {
	Tenant             string
	Namespace          string
	AgentID            string
	BindingID          string
	AgentKey           string
	InstanceKey        string
	InstanceGeneration int64
}

// EventSink processes upstream events from data plane instances.
type EventSink interface {
	HandleConnect(tenant, namespace, agentID, bindingID, agentKey, instanceKey string, generation int64, runtime, sdkVersion string, capabilities []string)
	HandleDisconnect(tenant, namespace, agentID, bindingID, instanceKey string, generation int64)
	HandleSessionReport(identity ReportIdentity, report *SessionReport)
	HandleExecutionAttemptReport(tenant, namespace, agentID, bindingID, instanceKey string, instanceGeneration int64, report *ExecutionAttemptReport)
	HandleConversationTurnReport(identity ReportIdentity, report *ConversationTurnReport)
	// HandleEventReport processes a Level-2 event stream batch (session_events).
	// HandleEventReport returns durable per-session commit watermarks. The
	// transport must not acknowledge an event that has not reached the Store.
	HandleEventReport(identity ReportIdentity, report *EventReport) *EventReportAck
	// HandleContextReport processes a Level-4 effective-context report (context_snapshots).
	HandleContextReport(identity ReportIdentity, report *ContextReport)
	// HandleInventoryReport processes an instance inventory report. The latest
	// report is also kept in the server connection registry (see GetInventory*).
	HandleInventoryReport(identity ReportIdentity, report *InventoryReport)
}

// InstanceInventory couples the latest InventoryReport from a connected
// instance with the time it was received.
type InstanceInventory struct {
	Tenant     string
	Namespace  string
	AgentID    string
	BindingID  string
	AgentName  string
	InstanceID string
	Generation int64
	Report     *InventoryReport
	UpdatedAt  time.Time
}

// ServerConfig holds configuration for the ASDP gRPC server.
type ServerConfig struct {
	Addr      string
	AuthToken string
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
		authToken:   cfg.AuthToken,
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

func (s *Server) SetIdentityValidator(validator IdentityValidator) {
	s.identityValidator = validator
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
	key := GetInstanceKey(conn.Tenant, conn.Namespace, conn.AgentID, conn.InstanceID)
	s.connections[key] = conn
	metrics.RecordGRPCConnection(1)
	s.mu.Unlock()
	if s.eventSink != nil {
		s.eventSink.HandleConnect(conn.Tenant, conn.Namespace, conn.AgentID, conn.BindingID, conn.AgentName,
			conn.InstanceID, conn.Generation, conn.Runtime, conn.SDKVersion, append([]string(nil), conn.Capabilities...))
	}

	logger := log.Log.WithName("asdp")
	logger.Info("data plane connected",
		"agent", conn.AgentName,
		"instance", conn.InstanceID,
		"runtime", conn.Runtime,
		"capabilities", conn.Capabilities,
	)
}

// UnregisterConnection removes a data plane connection.
func (s *Server) UnregisterConnection(tenant, namespace, agentID, instanceID string) {
	s.mu.Lock()
	key := GetInstanceKey(tenant, namespace, agentID, instanceID)
	var disconnected *Connection
	if conn, ok := s.connections[key]; ok {
		disconnected = conn
		conn.Close()
		delete(s.connections, key)
		metrics.RecordGRPCConnection(-1)
	}
	delete(s.inventory, key)
	s.mu.Unlock()
	if disconnected != nil && s.eventSink != nil {
		s.eventSink.HandleDisconnect(disconnected.Tenant, disconnected.Namespace, disconnected.AgentID,
			disconnected.BindingID, disconnected.InstanceID, disconnected.Generation)
	}

	logger := log.Log.WithName("asdp")
	logger.Info("data plane disconnected", "instance", instanceID, "namespace", namespace)
}

// GetConnection retrieves an unambiguous connection by namespace and instance.
// Tenant-aware task routing must use GetConnectionForTenant.
func (s *Server) GetConnection(namespace, instanceID string) (*Connection, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var found *Connection
	for _, conn := range s.connections {
		if conn.Namespace != namespace || conn.InstanceID != instanceID {
			continue
		}
		if found != nil {
			return nil, false
		}
		found = conn
	}
	return found, found != nil
}

// GetConnectionForTenant retrieves a connection without crossing a tenant boundary.
func (s *Server) GetConnectionForTenant(tenant, namespace, instanceID string) (*Connection, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var found *Connection
	for _, conn := range s.connections {
		if conn.Tenant != tenant || conn.Namespace != namespace || conn.InstanceID != instanceID {
			continue
		}
		if found != nil {
			return nil, false
		}
		found = conn
	}
	return found, found != nil
}

// GetConnectionForAgentInstance retrieves the exact Agent connection without assuming that an
// instance key is globally unique across different Agents running on the same host.
func (s *Server) GetConnectionForAgentInstance(tenant, namespace, agentID, instanceID string) (*Connection, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	conn, ok := s.connections[GetInstanceKey(tenant, namespace, agentID, instanceID)]
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

// GetConnectionsForAgentForTenant returns connections in one collaboration scope.
func (s *Server) GetConnectionsForAgentForTenant(tenant, namespace, agentName string) []*Connection {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var conns []*Connection
	for _, conn := range s.connections {
		if conn.Tenant == tenant && conn.Namespace == namespace && conn.AgentName == agentName {
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
func (s *Server) UpdateInventory(tenant, namespace, agentID, instanceID string, report *InventoryReport) {
	s.mu.Lock()
	defer s.mu.Unlock()

	key := GetInstanceKey(tenant, namespace, agentID, instanceID)
	conn, ok := s.connections[key]
	if !ok {
		return
	}
	s.inventory[key] = &InstanceInventory{
		Tenant:     tenant,
		Namespace:  namespace,
		AgentID:    conn.AgentID,
		BindingID:  conn.BindingID,
		AgentName:  conn.AgentName,
		InstanceID: instanceID,
		Generation: conn.Generation,
		Report:     report,
		UpdatedAt:  time.Now().UTC(),
	}
}

// GetInventory returns the latest inventory for a specific instance.
func (s *Server) GetInventory(namespace, instanceID string) (*InstanceInventory, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var found *InstanceInventory
	for _, inv := range s.inventory {
		if inv.Namespace != namespace || inv.InstanceID != instanceID {
			continue
		}
		if found != nil {
			return nil, false
		}
		found = inv
	}
	return found, found != nil
}

// GetInventoriesForAgent returns the latest inventories of every connected
// instance of the given agent in one tenant/namespace boundary.
func (s *Server) GetInventoriesForAgent(tenant, namespace, agentName string) []*InstanceInventory {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var out []*InstanceInventory
	for _, inv := range s.inventory {
		if inv.Tenant == tenant && inv.Namespace == namespace && inv.AgentName == agentName {
			out = append(out, inv)
		}
	}
	return out
}
