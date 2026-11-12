// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"golang.org/x/sync/errgroup"
)

type webhookProof struct {
	Version    int    `json:"version"`
	WebhookID  string `json:"webhookId"`
	DeliveryID string `json:"deliveryId"`
	ClaimToken string `json:"claimToken"`
	ClaimFence int64  `json:"claimFence"`
}
type webhookClaim struct {
	webhookProof
	SessionID     string `json:"sessionId"`
	LeaseUntil    int64  `json:"leaseUntil"`
	URL           string `json:"url"`
	SigningSecret string `json:"signingSecret"`
	Payload       string `json:"payload"`
}
type agentServiceWebhookWorker struct {
	base, token, workerID string
	slots                 int
	pollInterval          time.Duration
	client, sender        *http.Client
	allowed               map[string]bool
	loopback              bool
}

func newAgentServiceWebhookWorker(base, token string) (*agentServiceWebhookWorker, error) {
	slots, err := schedulerSlots("MANAGED_AGENTS_WEBHOOK_SLOTS", 8)
	if err != nil {
		return nil, err
	}
	poll, err := schedulerMilliseconds("MANAGED_AGENTS_WEBHOOK_POLL_MS", 1000)
	if err != nil {
		return nil, err
	}
	noRedirect := func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	pool := http.DefaultTransport.(*http.Transport).Clone()
	pool.MaxConnsPerHost = slots
	pool.MaxIdleConnsPerHost = slots
	sendPool := pool.Clone()
	w := &agentServiceWebhookWorker{base: strings.TrimRight(base, "/"), token: token, workerID: uuid.NewString(), slots: slots, pollInterval: poll, client: &http.Client{Timeout: 5 * time.Second, Transport: pool, CheckRedirect: noRedirect}, sender: &http.Client{Timeout: 10 * time.Second, Transport: sendPool, CheckRedirect: noRedirect}, allowed: map[string]bool{}, loopback: strings.EqualFold(os.Getenv("MANAGED_AGENTS_WEBHOOK_ALLOW_LOOPBACK_HTTP"), "true")}
	for _, host := range strings.Split(os.Getenv("MANAGED_AGENTS_WEBHOOK_ALLOWED_HOSTS"), ",") {
		if host = strings.TrimSpace(strings.ToLower(host)); host != "" {
			w.allowed[host] = true
		}
	}
	return w, nil
}
func (w *agentServiceWebhookWorker) close() {
	w.client.CloseIdleConnections()
	w.sender.CloseIdleConnections()
}
func (w *agentServiceWebhookWorker) run(ctx context.Context) error {
	if w.base == "" || w.token == "" {
		<-ctx.Done()
		return nil
	}
	group, workerCtx := errgroup.WithContext(ctx)
	for range w.slots {
		group.Go(func() error {
			ticker := time.NewTicker(w.pollInterval)
			defer ticker.Stop()
			for {
				select {
				case <-workerCtx.Done():
					return nil
				case <-ticker.C:
					stepCtx, cancel := context.WithTimeout(workerCtx, 25*time.Second)
					_ = w.poll(stepCtx)
					cancel()
				}
			}
		})
	}
	return group.Wait()
}
func (w *agentServiceWebhookWorker) call(ctx context.Context, path string, input, out any) error {
	raw, err := json.Marshal(input)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, "POST", w.base+"/api/internal/runtime/webhooks/"+path, bytes.NewReader(raw))
	if err != nil {
		return fmt.Errorf("invalid Agent Service URL")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Managed-Agents-Internal-Token", w.token)
	resp, err := w.client.Do(req)
	if err != nil {
		return fmt.Errorf("Agent Service webhook transport unavailable")
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("Agent Service webhook HTTP %d", resp.StatusCode)
	}
	raw, err = io.ReadAll(io.LimitReader(resp.Body, 524289))
	if err != nil || len(raw) > 524288 {
		return fmt.Errorf("invalid Agent Service webhook response")
	}
	if out != nil && json.Unmarshal(raw, out) != nil {
		return fmt.Errorf("invalid Agent Service webhook JSON")
	}
	return nil
}
func (w *agentServiceWebhookWorker) poll(ctx context.Context) error {
	var batch struct {
		Version    int            `json:"version"`
		Deliveries []webhookClaim `json:"deliveries"`
	}
	if err := w.call(ctx, "claim", map[string]any{"version": 1, "workerId": w.workerID, "limit": 1}, &batch); err != nil {
		return err
	}
	if batch.Version != 1 || len(batch.Deliveries) > 1 {
		return fmt.Errorf("invalid webhook claim batch")
	}
	for _, claim := range batch.Deliveries {
		if err := w.deliver(ctx, claim); err != nil {
			return err
		}
	}
	return nil
}
func (w *agentServiceWebhookWorker) deliver(ctx context.Context, d webhookClaim) error {
	if d.Version != 1 || d.ClaimFence < 1 || d.ClaimToken == "" || d.WebhookID == "" || d.DeliveryID == "" || d.SessionID == "" || len(d.URL) > 4096 || len(d.Payload) > 8192 || len(d.SigningSecret) > 256 || d.LeaseUntil <= time.Now().Add(15*time.Second).UnixMilli() {
		return fmt.Errorf("invalid webhook claim")
	}
	var decision struct {
		Version    int    `json:"version"`
		Allowed    bool   `json:"allowed"`
		DeliveryID string `json:"deliveryId"`
		ClaimFence int64  `json:"claimFence"`
	}
	if err := w.call(ctx, "validate", d.webhookProof, &decision); err != nil {
		return err
	}
	if decision.Version != 1 || !decision.Allowed || decision.DeliveryID != d.DeliveryID || decision.ClaimFence != d.ClaimFence || d.LeaseUntil <= time.Now().Add(15*time.Second).UnixMilli() {
		return nil
	}
	status, code := 0, ""
	destination, err := w.destination(d.URL)
	if err != nil {
		code = "blocked"
	} else {
		stamp := strconv.FormatInt(time.Now().Unix(), 10)
		req, requestErr := http.NewRequestWithContext(ctx, "POST", destination, strings.NewReader(d.Payload))
		if requestErr != nil {
			code = "blocked"
		} else {
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Managed-Agents-Delivery", d.DeliveryID)
			req.Header.Set("X-Managed-Agents-Timestamp", stamp)
			req.Header.Set("X-Managed-Agents-Signature", webhookSign(d.SigningSecret, stamp, d.Payload))
			response, sendErr := w.sender.Do(req)
			if sendErr != nil {
				code = "transport"
			} else {
				status = response.StatusCode
				response.Body.Close()
			}
		}
	}
	return w.call(ctx, "receipts", map[string]any{"proof": d.webhookProof, "httpStatus": status, "errorCode": code}, nil)
}
func (w *agentServiceWebhookWorker) destination(value string) (string, error) {
	u, err := url.Parse(value)
	if err != nil || u.User != nil || u.Fragment != "" || u.Host == "" {
		return "", fmt.Errorf("invalid webhook destination")
	}
	host := strings.ToLower(u.Hostname())
	u.Scheme = strings.ToLower(u.Scheme)
	local := w.loopback && u.Scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")
	secure := u.Scheme == "https" && (u.Port() == "" || u.Port() == "443") && w.allowed[host]
	if !local && !secure {
		return "", fmt.Errorf("webhook destination blocked")
	}
	return u.String(), nil
}
func webhookSign(secret, stamp, payload string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(stamp + "." + payload))
	return "v1=" + hex.EncodeToString(mac.Sum(nil))
}
