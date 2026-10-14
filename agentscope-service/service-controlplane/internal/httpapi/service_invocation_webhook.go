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
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/secretcrypto"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/sessionapi"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type serviceWebhook struct {
	ID          uuid.UUID `json:"id"`
	URL         string    `json:"url"`
	Types       []string  `json:"event_types"`
	Status      string    `json:"status"`
	Cursor      string    `json:"cursor"`
	Attempts    int       `json:"attempts"`
	NextAttempt time.Time `json:"next_attempt,omitempty"`
	LastError   string    `json:"last_error,omitempty"`
	Ciphertext  []byte    `json:"ciphertext,omitempty"`
}

func webhookPath(id uuid.UUID) string               { return "service-api/webhooks/" + id.String() }
func webhookPublic(w serviceWebhook) serviceWebhook { w.Ciphertext = nil; return w }
func (s *Server) createServiceWebhook(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "webhooks:write")
	if !ok {
		return
	}
	var req struct {
		URL   string   `json:"url"`
		Types []string `json:"event_types"`
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 32<<10)
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	u, err := url.Parse(req.URL)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
		c.JSON(400, ErrorResponse{Error: "webhook URL must use HTTPS without credentials or fragment"})
		return
	}
	key := c.GetHeader("Idempotency-Key")
	if key == "" {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key is required"})
		return
	}
	if len(req.Types) == 0 {
		req.Types = []string{"invocation.completed", "invocation.partial_succeeded", "invocation.failed", "invocation.cancelled", "invocation.timed_out", "required_action.created"}
	}
	if frozen, e := serviceapi.ReadContract(inv.Contract); e == nil && frozen.PublicSessionID != uuid.Nil {
		for i, t := range req.Types {
			req.Types[i] = strings.Replace(t, "invocation.", "turn.", 1)
		}
	}
	for _, t := range req.Types {
		if !strings.HasPrefix(t, "turn.") && !strings.HasPrefix(t, "invocation.") && !strings.HasPrefix(t, "required_action.") && !strings.HasPrefix(t, "artifact.") && t != "budget.exceeded" {
			c.JSON(400, ErrorResponse{Error: "unsupported webhook event_type: " + t})
			return
		}
	}
	id := uuid.NewSHA1(inv.ID, []byte("webhook:"+key))
	path := webhookPath(inv.ID)
	if item, err := s.store.KV().Get(c, ep.Tenant, path, id.String()); err == nil {
		var w serviceWebhook
		_ = json.Unmarshal(item.Value, &w)
		if w.URL != req.URL || serviceFingerprint(w.Types) != serviceFingerprint(req.Types) {
			c.JSON(409, ErrorResponse{Error: "Idempotency-Key was used for another webhook"})
			return
		}
		secret, err := secretcrypto.Decrypt(s.endpointCredentialKey, w.Ciphertext, []byte(id.String()))
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		c.JSON(200, gin.H{"webhook": webhookPublic(w), "signing_secret": string(secret)})
		return
	} else if !errors.Is(err, store.ErrNotFound) {
		s.writeControlPlaneError(c, err)
		return
	}
	rawSecret := make([]byte, 32)
	if _, err = rand.Read(rawSecret); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	secret := base64.RawURLEncoding.EncodeToString(rawSecret)
	ciphertext, err := secretcrypto.Encrypt(s.endpointCredentialKey, []byte(secret), []byte(id.String()))
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	w := serviceWebhook{ID: id, URL: req.URL, Types: req.Types, Status: "active", Ciphertext: ciphertext}
	raw, _ := json.Marshal(w)
	if _, written, err := s.store.KV().PutIfVersion(c, ep.Tenant, path, id.String(), raw, 0); err != nil {
		s.writeControlPlaneError(c, err)
		return
	} else if !written {
		c.JSON(409, ErrorResponse{Error: "webhook concurrently created; retry with the same key"})
		return
	}
	if err = s.schedulePublicWebhook(c, inv); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.JSON(201, gin.H{"webhook": webhookPublic(w), "signing_secret": secret})
}
func (s *Server) listServiceWebhooks(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "webhooks:write")
	if !ok {
		return
	}
	result := []serviceWebhook{}
	for offset := 0; ; offset += 100 {
		rows, err := s.store.KV().Search(c, ep.Tenant, webhookPath(inv.ID), 100, offset)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return
		}
		for _, row := range rows {
			var w serviceWebhook
			if err = json.Unmarshal(row.Value, &w); err != nil {
				s.writeControlPlaneError(c, err)
				return
			}
			result = append(result, webhookPublic(w))
		}
		if len(rows) < 100 {
			break
		}
	}
	c.JSON(200, gin.H{"data": result})
}
func (s *Server) updateServiceWebhook(c *gin.Context) {
	inv, ep, ok := s.loadServiceInvocation(c, "webhooks:write")
	if !ok {
		return
	}
	id, err := uuid.Parse(c.Param("webhookId"))
	if err != nil {
		c.JSON(400, ErrorResponse{Error: "invalid webhookId"})
		return
	}
	err = s.store.WithSessionLock(c, "service-webhook:"+id.String(), func(ctx context.Context) error {
		row, err := s.store.KV().Get(ctx, ep.Tenant, webhookPath(inv.ID), id.String())
		if err != nil {
			return err
		}
		var w serviceWebhook
		if err = json.Unmarshal(row.Value, &w); err != nil {
			return err
		}
		if c.Request.Method == http.MethodDelete {
			w.Status = "disabled"
		} else {
			w.Status = "active"
			w.Attempts = 0
			w.NextAttempt = time.Time{}
			w.LastError = ""
		}
		raw, _ := json.Marshal(w)
		_, err = s.store.KV().Put(ctx, ep.Tenant, webhookPath(inv.ID), id.String(), raw)
		return err
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	if err = s.schedulePublicWebhook(c, inv); err != nil {
		s.writeControlPlaneError(c, err)
		return
	}
	c.Status(204)
}

// Resolve each delivery and dial the validated address directly, avoiding DNS rebinding.
func serviceWebhookClient() *http.Client {
	transport := &http.Transport{TLSHandshakeTimeout: 5 * time.Second, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
		if err != nil {
			return nil, err
		}
		for _, ip := range ips {
			addr, _ := netip.AddrFromSlice(ip.IP)
			addr = addr.Unmap()
			for _, reserved := range []string{"100.64.0.0/10", "192.0.0.0/24", "198.18.0.0/15"} {
				if netip.MustParsePrefix(reserved).Contains(addr) {
					return nil, fmt.Errorf("webhook destination is not public")
				}
			}
			if !ip.IP.IsGlobalUnicast() || ip.IP.IsLoopback() || ip.IP.IsPrivate() || ip.IP.IsLinkLocalUnicast() || ip.IP.IsLinkLocalMulticast() || ip.IP.IsUnspecified() || ip.IP.IsMulticast() {
				return nil, fmt.Errorf("webhook destination is not public")
			}
		}
		for _, ip := range ips {
			conn, err := (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, network, net.JoinHostPort(ip.IP.String(), port))
			if err == nil {
				return conn, nil
			}
		}
		return nil, fmt.Errorf("webhook destination is unavailable")
	}}
	return &http.Client{Transport: transport, Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}
func (s *Server) deliverServiceWebhooks(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint) error {
	client := serviceWebhookClient()
	defer client.CloseIdleConnections()
	return s.deliverServiceWebhooksWith(ctx, inv, ep, client, time.Now)
}

// Dependencies stay private: production always uses the validated public-only
// client above; tests can exercise real TLS delivery with an isolated receiver.
func (s *Server) deliverServiceWebhooksWith(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint, client *http.Client, now func() time.Time) error {
	for offset := 0; ; offset += 100 {
		rows, err := s.store.KV().Search(ctx, ep.Tenant, webhookPath(inv.ID), 100, offset)
		if err != nil {
			return err
		}
		for _, row := range rows {
			var initial serviceWebhook
			if err = json.Unmarshal(row.Value, &initial); err != nil {
				return err
			}
			if initial.Status != "active" || now().Before(initial.NextAttempt) {
				continue
			}
			err = s.store.WithSessionLock(ctx, "service-webhook:"+initial.ID.String(), func(ctx context.Context) error {
				current, err := s.store.KV().Get(ctx, ep.Tenant, webhookPath(inv.ID), row.Key)
				if err != nil {
					return err
				}
				var w serviceWebhook
				if err = json.Unmarshal(current.Value, &w); err != nil {
					return err
				}
				if w.Status != "active" || now().Before(w.NextAttempt) {
					return nil
				}
				events, _, _, err := serviceJournal(s.store, ep, inv).Events(ctx, w.Cursor, 20)
				if errors.Is(err, serviceapi.ErrCursorExpired) {
					w.Status = "failed"
					w.LastError = "event history expired; load the Invocation snapshot"
					raw, _ := json.Marshal(w)
					_, err = s.store.KV().Put(ctx, ep.Tenant, webhookPath(inv.ID), row.Key, raw)
					return err
				}
				if err != nil {
					return err
				}
				for _, event := range events {
					selected := false
					for _, kind := range w.Types {
						eventType := event.Type
						if frozen, e := serviceapi.ReadContract(inv.Contract); e == nil && frozen.PublicSessionID != uuid.Nil {
							eventType = strings.Replace(eventType, "invocation.", "turn.", 1)
						}
						if kind == eventType {
							selected = true
						}
					}
					if selected {
						secret, err := secretcrypto.Decrypt(s.endpointCredentialKey, w.Ciphertext, []byte(w.ID.String()))
						if err != nil {
							return err
						}
						var eventBody any = event
						if frozen, e := serviceapi.ReadContract(inv.Contract); e == nil && frozen.PublicSessionID != uuid.Nil {
							v := &sessionapi.Session{ID: frozen.PublicSessionID}
							if inv.ID != v.ID {
								event.Data["turn_id"] = inv.ID.String()
							}
							eventBody = publicEventView(v, event)
						}
						raw, _ := json.Marshal(eventBody)
						stamp := strconv.FormatInt(now().Unix(), 10)
						mac := hmac.New(sha256.New, secret)
						_, _ = mac.Write([]byte(stamp + "."))
						_, _ = mac.Write(raw)
						req, err := http.NewRequestWithContext(ctx, http.MethodPost, w.URL, bytes.NewReader(raw))
						if err != nil {
							return err
						}
						req.Header.Set("Content-Type", "application/json")
						req.Header.Set("X-AgentScope-Event-ID", event.ID)
						req.Header.Set("X-AgentScope-Signature", "t="+stamp+",v1="+hex.EncodeToString(mac.Sum(nil)))
						resp, err := client.Do(req)
						if resp != nil {
							_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
							resp.Body.Close()
							if resp.StatusCode < 200 || resp.StatusCode >= 300 {
								err = fmt.Errorf("HTTP %d", resp.StatusCode)
							}
						}
						if err != nil {
							w.Attempts++
							w.LastError = err.Error()
							w.NextAttempt = now().Add(time.Duration(1<<min(w.Attempts, 10)) * time.Second)
							if w.Attempts >= 12 {
								w.Status = "failed"
							}
							break
						}
					}
					w.Cursor = event.Cursor
					w.Attempts = 0
					w.LastError = ""
					w.NextAttempt = time.Time{}
				}
				raw, _ := json.Marshal(w)
				_, err = s.store.KV().Put(ctx, ep.Tenant, webhookPath(inv.ID), row.Key, raw)
				return err
			})
			if err != nil {
				return err
			}
		}
		if len(rows) < 100 {
			return nil
		}
	}
}
