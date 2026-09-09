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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

// Package realtime provides best-effort, scope-filtered cache invalidation.
// Durable collaboration truth remains in PostgreSQL and Activity; a client
// that reconnects always refreshes through the normal REST API.
package realtime

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type subscriber struct {
	tenant    string
	namespace string
	events    chan *controlmodel.OutboxEvent
}

// Hub fans out versioned outbox events to connected Console clients. Slow
// clients are disconnected rather than applying backpressure to the durable
// outbox dispatcher.
type Hub struct {
	mu          sync.RWMutex
	subscribers map[*subscriber]struct{}
}

func NewHub() *Hub { return &Hub{subscribers: make(map[*subscriber]struct{})} }

func (h *Hub) PublishCollaborationEvent(_ context.Context, event *controlmodel.OutboxEvent) error {
	if h == nil || event == nil {
		return nil
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for sub := range h.subscribers {
		if sub.tenant != event.Tenant || sub.namespace != event.Namespace {
			continue
		}
		select {
		case sub.events <- event:
		default:
			// Closing is owned by Serve; a full channel simply forces that
			// client to recover through its periodic REST refresh.
		}
	}
	return nil
}

func (h *Hub) Serve(w http.ResponseWriter, r *http.Request, tenant, namespace string) {
	h.ServeAuthorized(w, r, tenant, namespace, nil)
}

// ServeAuthorized rechecks permission at delivery, including after revocation.
func (h *Hub) ServeAuthorized(w http.ResponseWriter, r *http.Request, tenant, namespace string, allowed func(context.Context, *controlmodel.OutboxEvent) bool) {
	requestedProtocols := websocket.Subprotocols(r)
	selectedProtocol := ""
	for _, protocol := range requestedProtocols {
		if protocol == "aistio.v1" {
			selectedProtocol = protocol
			break
		}
	}
	upgrader := websocket.Upgrader{
		CheckOrigin: func(request *http.Request) bool {
			origin := request.Header.Get("Origin")
			return origin == "" || origin == "http://"+request.Host || origin == "https://"+request.Host
		},
	}
	responseHeader := http.Header{}
	if selectedProtocol != "" {
		responseHeader.Set("Sec-WebSocket-Protocol", selectedProtocol)
	}
	conn, err := upgrader.Upgrade(w, r, responseHeader)
	if err != nil {
		return
	}
	defer conn.Close()

	sub := &subscriber{tenant: tenant, namespace: namespace, events: make(chan *controlmodel.OutboxEvent, 64)}
	h.mu.Lock()
	h.subscribers[sub] = struct{}{}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.subscribers, sub)
		h.mu.Unlock()
	}()

	_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	})
	closed := make(chan struct{})
	go func() {
		defer close(closed)
		for {
			if _, _, readErr := conn.ReadMessage(); readErr != nil {
				return
			}
		}
	}()

	ticker := time.NewTicker(25 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-closed:
			return
		case event := <-sub.events:
			if allowed != nil && !allowed(r.Context(), event) {
				continue
			}
			if err := conn.WriteJSON(event); err != nil {
				return
			}
		case <-ticker.C:
			if err := conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)); err != nil {
				return
			}
		}
	}
}
