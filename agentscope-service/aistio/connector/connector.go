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

package connector

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"os"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/internal/asdp"
)

// Config holds the connector configuration.
type Config struct {
	ControlPlaneAddr string
	AgentName        string
	InstanceID       string
	Namespace        string
	Runtime          string
	SDKVersion       string
	Capabilities     []string
	SessionAffinity  string

	// TLS config (optional)
	TLSCertFile string
	TLSKeyFile  string
	TLSCAFile   string

	// SessionReportInterval is how often to send session reports (default 10s).
	SessionReportInterval time.Duration

	// OnConfigPush is called when the control plane pushes a config update.
	// The handler should return true (ACK) if it accepted the config, false (NACK) with a reason.
	OnConfigPush func(configType asdp.ConfigType, version string, resources []byte) (accepted bool, rejectReason string)

	// OnSessionCommand is called when the control plane sends a session command.
	OnSessionCommand func(sessionID string, command string, params []byte)
}

// Connector manages the gRPC connection to the control plane.
type Connector struct {
	cfg    Config
	conn   *grpc.ClientConn
	cancel context.CancelFunc

	mu       sync.Mutex
	sessions []*asdp.SessionSnapshot

	// sendMu guards the active send channel of the current connection
	// generation. Report* APIs enqueue best-effort through it.
	sendMu sync.RWMutex
	sendCh chan *asdp.Upstream
	runCtx context.Context
}

// New creates a new Connector.
func New(cfg Config) *Connector {
	if cfg.SessionReportInterval == 0 {
		cfg.SessionReportInterval = 10 * time.Second
	}
	return &Connector{cfg: cfg}
}

// Start connects to the control plane and begins the ASDP stream.
// Blocks until the context is cancelled or the connection is lost.
// Automatically reconnects with exponential backoff.
func (c *Connector) Start(ctx context.Context) error {
	logger := log.Log.WithName("connector")

	ctx, cancel := context.WithCancel(ctx)
	c.cancel = cancel
	defer cancel()

	backoff := time.Second
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		err := c.connectAndRun(ctx)
		if ctx.Err() != nil {
			return ctx.Err()
		}

		logger.Error(err, "connection lost, reconnecting", "backoff", backoff)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}

		backoff = min(backoff, 30*time.Second)
		if backoff < 30*time.Second {
			backoff *= 2
		}
	}
}

func (c *Connector) connectAndRun(ctx context.Context) error {
	logger := log.Log.WithName("connector")

	creds, err := c.dialCredentials()
	if err != nil {
		return fmt.Errorf("tls credentials: %w", err)
	}

	conn, err := grpc.NewClient(c.cfg.ControlPlaneAddr, grpc.WithTransportCredentials(creds))
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}
	defer conn.Close()
	c.conn = conn

	// runCtx is cancelled when the writer goroutine fails so the recv loop
	// unblocks and the connection is retried.
	runCtx, runCancel := context.WithCancel(ctx)
	defer runCancel()

	client := asdp.NewAgentDataPlaneServiceClient(conn)
	stream, err := client.Connect(runCtx)
	if err != nil {
		return fmt.Errorf("connect: %w", err)
	}

	// Send handshake. This is the only Send before the writer goroutine starts,
	// so it is safe to call stream.Send directly here.
	if err := stream.Send(&asdp.Upstream{
		Meta: c.buildMeta(),
		Payload: &asdp.Upstream_Connect{
			Connect: &asdp.ConnectRequest{
				Runtime:         c.cfg.Runtime,
				SdkVersion:      c.cfg.SDKVersion,
				Capabilities:    c.cfg.Capabilities,
				SessionAffinity: c.cfg.SessionAffinity,
			},
		},
	}); err != nil {
		return fmt.Errorf("handshake send: %w", err)
	}

	// Wait for handshake response
	firstResp, err := stream.Recv()
	if err != nil {
		return fmt.Errorf("handshake recv: %w", err)
	}
	ack := firstResp.GetConnectAck()
	if ack == nil || !ack.Accepted {
		reason := "unknown"
		if ack != nil {
			reason = ack.RejectReason
		}
		return fmt.Errorf("handshake rejected: %s", reason)
	}
	logger.Info("connected to control plane",
		"cpVersion", ack.ControlPlaneVersion,
		"agent", c.cfg.AgentName,
	)

	// gRPC client streams are NOT safe for concurrent Send. Funnel every
	// post-handshake send through a single writer goroutine via sendCh.
	sendCh := make(chan *asdp.Upstream, 256)
	c.sendMu.Lock()
	c.sendCh, c.runCtx = sendCh, runCtx
	c.sendMu.Unlock()
	defer func() {
		c.sendMu.Lock()
		c.sendCh, c.runCtx = nil, nil
		c.sendMu.Unlock()
	}()
	go func() {
		for {
			select {
			case <-runCtx.Done():
				return
			case msg := <-sendCh:
				if err := stream.Send(msg); err != nil {
					logger.Error(err, "stream send failed; closing connection")
					runCancel()
					return
				}
			}
		}
	}()

	// Start session report ticker (enqueues through sendCh).
	go c.sessionReportLoop(runCtx, sendCh)

	// Recv loop
	for {
		msg, err := stream.Recv()
		if err != nil {
			if err == io.EOF {
				return fmt.Errorf("stream closed by server")
			}
			return fmt.Errorf("recv: %w", err)
		}
		c.handleDownstream(runCtx, sendCh, msg)
	}
}

// dialCredentials builds the gRPC transport credentials from the configured TLS
// material. When no CA or client cert is configured it falls back to an insecure
// connection (suitable for in-cluster, network-policy-protected deployments).
func (c *Connector) dialCredentials() (credentials.TransportCredentials, error) {
	if c.cfg.TLSCAFile == "" && c.cfg.TLSCertFile == "" {
		return insecure.NewCredentials(), nil
	}

	tlsCfg := &tls.Config{MinVersion: tls.VersionTLS12}
	if c.cfg.TLSCAFile != "" {
		caPEM, err := os.ReadFile(c.cfg.TLSCAFile)
		if err != nil {
			return nil, fmt.Errorf("read CA file: %w", err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(caPEM) {
			return nil, fmt.Errorf("parse CA file %s", c.cfg.TLSCAFile)
		}
		tlsCfg.RootCAs = pool
	}
	if c.cfg.TLSCertFile != "" && c.cfg.TLSKeyFile != "" {
		cert, err := tls.LoadX509KeyPair(c.cfg.TLSCertFile, c.cfg.TLSKeyFile)
		if err != nil {
			return nil, fmt.Errorf("load client cert/key: %w", err)
		}
		tlsCfg.Certificates = []tls.Certificate{cert}
	}
	return credentials.NewTLS(tlsCfg), nil
}

// trySend enqueues a message to the writer goroutine without blocking past
// connection teardown.
func trySend(ctx context.Context, sendCh chan<- *asdp.Upstream, msg *asdp.Upstream) {
	select {
	case sendCh <- msg:
	case <-ctx.Done():
	}
}

func (c *Connector) handleDownstream(ctx context.Context, sendCh chan<- *asdp.Upstream, msg *asdp.Downstream) {
	logger := log.Log.WithName("connector")

	switch p := msg.Payload.(type) {
	case *asdp.Downstream_ConfigPush:
		push := p.ConfigPush
		logger.Info("config push received",
			"configType", push.ConfigType,
			"version", push.Version,
			"nonce", push.Nonce,
		)

		accepted := true
		rejectReason := ""
		if c.cfg.OnConfigPush != nil {
			accepted, rejectReason = c.cfg.OnConfigPush(push.ConfigType, push.Version, push.Resources)
		}

		trySend(ctx, sendCh, &asdp.Upstream{
			Meta: c.buildMeta(),
			Payload: &asdp.Upstream_ConfigAck{
				ConfigAck: &asdp.ConfigAck{
					ConfigType:   push.ConfigType,
					Version:      push.Version,
					Nonce:        push.Nonce,
					Accepted:     accepted,
					RejectReason: rejectReason,
				},
			},
		})

	case *asdp.Downstream_SessionCmd:
		cmd := p.SessionCmd
		logger.Info("session command received",
			"session", cmd.SessionId,
			"command", cmd.Command,
		)
		if c.cfg.OnSessionCommand != nil {
			c.cfg.OnSessionCommand(cmd.SessionId, cmd.Command, cmd.Params)
		}

	case *asdp.Downstream_Heartbeat:
		trySend(ctx, sendCh, &asdp.Upstream{
			Meta: c.buildMeta(),
			Payload: &asdp.Upstream_Heartbeat{
				Heartbeat: &asdp.Heartbeat{Timestamp: p.Heartbeat.Timestamp},
			},
		})

	case *asdp.Downstream_TeamEvent:
		logger.V(1).Info("team event received", "team", p.TeamEvent.TeamId, "type", p.TeamEvent.EventType)
	}
}

func (c *Connector) sessionReportLoop(ctx context.Context, sendCh chan<- *asdp.Upstream) {
	ticker := time.NewTicker(c.cfg.SessionReportInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			c.mu.Lock()
			sessions := make([]*asdp.SessionSnapshot, len(c.sessions))
			copy(sessions, c.sessions)
			c.mu.Unlock()

			if len(sessions) == 0 {
				continue
			}

			trySend(ctx, sendCh, &asdp.Upstream{
				Meta: c.buildMeta(),
				Payload: &asdp.Upstream_SessionReport{
					SessionReport: &asdp.SessionReport{Sessions: sessions},
				},
			})
		}
	}
}

// UpdateSessions sets the current session snapshots to report.
func (c *Connector) UpdateSessions(sessions []*asdp.SessionSnapshot) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sessions = sessions
}

// enqueue delivers a message to the writer goroutine on a best-effort basis:
// it is silently dropped when disconnected or when the queue is full
// (reporting must never block or fail the host agent's main path).
func (c *Connector) enqueue(msg *asdp.Upstream) {
	c.sendMu.RLock()
	ch, ctx := c.sendCh, c.runCtx
	c.sendMu.RUnlock()
	if ch == nil || ctx == nil {
		return
	}
	select {
	case ch <- msg:
	case <-ctx.Done():
	default:
	}
}

// ReportEvents enqueues a Level-2 event-stream batch (session_events).
func (c *Connector) ReportEvents(events []*asdp.SessionEventMsg) {
	if len(events) == 0 {
		return
	}
	c.enqueue(&asdp.Upstream{
		Meta:    c.buildMeta(),
		Payload: &asdp.Upstream_EventReport{EventReport: &asdp.EventReport{Events: events}},
	})
}

// ReportContext enqueues a Level-4 effective-context snapshot.
func (c *Connector) ReportContext(report *asdp.ContextReport) {
	if report == nil {
		return
	}
	c.enqueue(&asdp.Upstream{
		Meta:    c.buildMeta(),
		Payload: &asdp.Upstream_ContextReport{ContextReport: report},
	})
}

// ReportInventory enqueues an instance inventory report (subagents,
// workspaces, health).
func (c *Connector) ReportInventory(report *asdp.InventoryReport) {
	if report == nil {
		return
	}
	c.enqueue(&asdp.Upstream{
		Meta:    c.buildMeta(),
		Payload: &asdp.Upstream_Inventory{Inventory: report},
	})
}

// Stop disconnects from the control plane.
func (c *Connector) Stop() {
	if c.cancel != nil {
		c.cancel()
	}
	if c.conn != nil {
		c.conn.Close()
	}
}

func (c *Connector) buildMeta() *asdp.UpstreamMeta {
	return &asdp.UpstreamMeta{
		AgentName:  c.cfg.AgentName,
		InstanceId: c.cfg.InstanceID,
		Namespace:  c.cfg.Namespace,
		Timestamp:  time.Now().Unix(),
	}
}
