// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package channels

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/gorilla/websocket"
	"net/url"
	"time"
)

// RunStream reconnects within the lifetime of the host's shared channel lease.
// Closing the context tears down the socket even when no frames arrive.
func (p *Provider) RunStream(ctx context.Context, accept Accept, report func(bool)) error {
	backoff := time.Second
	for ctx.Err() == nil {
		started := time.Now()
		_ = p.streamOnce(ctx, accept, report)
		if report != nil {
			report(false)
		}
		if time.Since(started) > time.Minute {
			backoff = time.Second
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, time.Minute)
	}
	return nil
}
func (p *Provider) streamOnce(ctx context.Context, accept Accept, reports ...func(bool)) error {
	endpoint := p.Property("streamRegisterUrl")
	if endpoint == "" {
		endpoint = p.base() + "/v1.0/gateway/connections/open"
	}
	registration, err := p.request(ctx, "POST", endpoint, nil, map[string]any{"clientId": p.Property("appKey"), "clientSecret": p.Property("appSecret"), "ua": "managed-agents/go", "subscriptions": []map[string]string{{"type": "CALLBACK", "topic": "/v1.0/im/bot/messages/get"}}})
	if err != nil {
		return err
	}
	u, err := url.Parse(value(registration, "endpoint"))
	if err != nil || u.Host == "" || (u.Scheme != "wss" && u.Scheme != "ws") || value(registration, "ticket") == "" {
		return fmt.Errorf("invalid stream registration")
	}
	q := u.Query()
	q.Set("ticket", value(registration, "ticket"))
	u.RawQuery = q.Encode()
	conn, resp, err := (&websocket.Dialer{HandshakeTimeout: 10 * time.Second}).DialContext(ctx, u.String(), nil)
	if resp != nil && resp.Body != nil {
		resp.Body.Close()
	}
	if err != nil {
		return fmt.Errorf("stream unavailable")
	}
	defer conn.Close()
	if len(reports) > 0 && reports[0] != nil {
		reports[0](true)
	}
	conn.SetReadLimit(1 << 20)
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			conn.Close()
		case <-done:
		}
	}()
	for {
		var frame map[string]any
		if err = conn.ReadJSON(&frame); err != nil {
			return err
		}
		typ := value(frame, "type")
		headers := object(frame, "headers")
		code := 200
		message := "OK"
		data := "{}"
		if typ == "SYSTEM" {
			switch value(headers, "topic") {
			case "disconnect":
				return fmt.Errorf("stream disconnect requested")
			case "ping":
				data = value(frame, "data")
			default:
				continue
			}
		} else if typ == "CALLBACK" {
			var body map[string]any
			if json.Unmarshal([]byte(value(frame, "data")), &body) != nil {
				code = 400
			} else if msg := p.dingtalk(body); msg != nil {
				intakeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
				err = accept(intakeCtx, *msg)
				cancel()
				if err != nil {
					code = 503
					message = "Retry"
				}
			}
		} else {
			continue
		}
		conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if err = conn.WriteJSON(map[string]any{"code": code, "headers": map[string]string{"messageId": value(headers, "messageId"), "contentType": "application/json"}, "message": message, "data": data}); err != nil {
			return err
		}
	}
}
