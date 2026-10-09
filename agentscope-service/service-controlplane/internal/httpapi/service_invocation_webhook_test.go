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
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

type webhookDelivery struct {
	body, signature, eventID string
}

func receivedWebhook(t *testing.T, deliveries <-chan webhookDelivery) webhookDelivery {
	t.Helper()
	select {
	case delivery := <-deliveries:
		return delivery
	case <-time.After(3 * time.Second):
		t.Fatal("webhook was not delivered")
		return webhookDelivery{}
	}
}

func webhookFixture(t *testing.T, st store.Store, receiver string) (*Server, *model.EndpointInvocation, *model.Endpoint, serviceWebhook, string) {
	t.Helper()
	ep := servicePostgresEndpoint(t, st, "webhook-"+uuid.NewString())
	contract, err := serviceapi.Freeze(t.Context(), st, ep)
	if err != nil {
		t.Fatal(err)
	}
	inv, _, err := st.Endpoints().ReserveInvocation(t.Context(), &model.EndpointInvocation{EndpointID: ep.ID, Mode: model.EndpointJobMode, PrincipalRef: "platform-static", Status: model.EndpointInvocationCompleted, Contract: contract})
	if err != nil {
		t.Fatal(err)
	}
	s := newLegacyEndpointTestServer(ServerOptions{Store: st, AuthToken: "webhook-fixture"})
	body, _ := json.Marshal(map[string]any{"url": receiver, "event_types": []string{"invocation.completed"}})
	req := httptest.NewRequest("POST", "/invoke/v1/invocations/"+inv.ID.String()+"/webhooks", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer webhook-fixture")
	req.Header.Set("Idempotency-Key", "subscription")
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create webhook: %d %s", w.Code, w.Body)
	}
	var response struct {
		Webhook serviceWebhook `json:"webhook"`
		Secret  string         `json:"signing_secret"`
	}
	if err = json.Unmarshal(w.Body.Bytes(), &response); err != nil || response.Secret == "" || len(response.Webhook.Ciphertext) != 0 {
		t.Fatalf("webhook response: %s %v", w.Body, err)
	}
	return s, inv, ep, response.Webhook, response.Secret
}

func readWebhook(t *testing.T, st store.Store, ep *model.Endpoint, inv *model.EndpointInvocation, id uuid.UUID) serviceWebhook {
	t.Helper()
	row, err := st.KV().Get(t.Context(), ep.Tenant, webhookPath(inv.ID), id.String())
	if err != nil {
		t.Fatal(err)
	}
	var w serviceWebhook
	if err := json.Unmarshal(row.Value, &w); err != nil {
		t.Fatal(err)
	}
	return w
}

func verifyWebhookSignature(t *testing.T, delivery webhookDelivery, secret string, now time.Time) {
	t.Helper()
	stamp := strconv.FormatInt(now.Unix(), 10)
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(stamp + "." + delivery.body))
	want := "t=" + stamp + ",v1=" + hex.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(delivery.signature), []byte(want)) {
		t.Fatalf("timestamp or raw-byte signature mismatch: %s", delivery.signature)
	}
	var event serviceapi.PublicEvent
	if err := json.Unmarshal([]byte(delivery.body), &event); err != nil || event.ID != delivery.eventID {
		t.Fatalf("header/body event ID mismatch: %+v %v", event, err)
	}
}

func TestServiceWebhookTLSBackoffPauseRetryAndSignature(t *testing.T) {
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	var responseStatus, redirects atomic.Int32
	responseStatus.Store(503)
	deliveries := make(chan webhookDelivery, 32)
	receiver := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil {
			t.Error("delivery did not use TLS")
		}
		if r.URL.Path == "/redirect-target" {
			redirects.Add(1)
			w.WriteHeader(204)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		deliveries <- webhookDelivery{string(raw), r.Header.Get("X-AgentScope-Signature"), r.Header.Get("X-AgentScope-Event-ID")}
		status := int(responseStatus.Load())
		if status == 0 {
			<-r.Context().Done()
			return
		}
		if status == http.StatusFound {
			w.Header().Set("Location", "/redirect-target")
		}
		w.WriteHeader(status)
	}))
	defer receiver.Close()
	client := receiver.Client() // trusts only this fixture's TLS certificate
	client.Timeout = 2 * time.Second
	client.CheckRedirect = serviceWebhookClient().CheckRedirect
	defer client.CloseIdleConnections()
	s, inv, ep, hook, secret := webhookFixture(t, st, receiver.URL)
	j := serviceJournal(st, ep, inv)
	ignored, err := j.Append(t.Context(), "ignored", "item.delta", map[string]any{"item_id": "text"})
	if err != nil {
		t.Fatal(err)
	}
	event, err := j.Append(t.Context(), "terminal", "invocation.completed", map[string]any{"output": "delivered"})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().Truncate(time.Second)
	clock := func() time.Time { return now }
	var first webhookDelivery
	statuses := []int32{400, 503, 0, http.StatusFound}
	for attempt := 1; attempt <= 12; attempt++ {
		client.Timeout = 2 * time.Second
		if attempt <= len(statuses) {
			responseStatus.Store(statuses[attempt-1])
		} else {
			responseStatus.Store(503)
		}
		if responseStatus.Load() == 0 {
			client.Timeout = 100 * time.Millisecond
		}
		if err := s.deliverServiceWebhooksWith(t.Context(), inv, ep, client, clock); err != nil {
			t.Fatal(err)
		}
		delivery := receivedWebhook(t, deliveries)
		verifyWebhookSignature(t, delivery, secret, now)
		if attempt == 1 {
			first = delivery
		} else if delivery.eventID != first.eventID || delivery.body != first.body || delivery.signature == first.signature {
			t.Fatal("redelivery changed event identity/body or reused stale timestamp signature")
		}
		state := readWebhook(t, st, ep, inv, hook.ID)
		if state.Attempts != attempt || state.Cursor != ignored.Cursor || state.LastError == "" {
			t.Fatalf("failure receipt: %+v", state)
		}
		if attempt < 12 && state.Status != "active" {
			t.Fatalf("subscription stopped before twelve failures: %+v", state)
		}
		wantDue := now.Add(time.Duration(1<<min(attempt, 10)) * time.Second)
		if !state.NextAttempt.Equal(wantDue) {
			t.Fatalf("backoff: got %s want %s", state.NextAttempt, wantDue)
		}
		if err := s.deliverServiceWebhooksWith(t.Context(), inv, ep, client, clock); err != nil {
			t.Fatal(err)
		}
		if len(deliveries) != 0 {
			t.Fatal("backoff was ignored")
		}
		now = state.NextAttempt
	}
	if state := readWebhook(t, st, ep, inv, hook.ID); state.Status != "failed" {
		t.Fatalf("twelfth failure must stop automatic delivery: %+v", state)
	}
	if redirects.Load() != 0 {
		t.Fatal("followed receiver redirect")
	}
	if err := s.deliverServiceWebhooksWith(t.Context(), inv, ep, client, clock); err != nil || len(deliveries) != 0 {
		t.Fatalf("failed subscription continued automatic delivery: %v", err)
	}
	request := httptest.NewRequest("POST", "/invoke/v1/invocations/"+inv.ID.String()+"/webhooks/"+hook.ID.String()+"/retry", nil)
	request.Header.Set("Authorization", "Bearer webhook-fixture")
	recorder := httptest.NewRecorder()
	s.router.ServeHTTP(recorder, request)
	if recorder.Code != 204 {
		t.Fatalf("retry webhook: %d %s", recorder.Code, recorder.Body)
	}
	responseStatus.Store(204)
	if err := s.deliverServiceWebhooksWith(t.Context(), inv, ep, client, clock); err != nil {
		t.Fatal(err)
	}
	delivery := receivedWebhook(t, deliveries)
	verifyWebhookSignature(t, delivery, secret, now)
	if delivery.eventID != event.ID || delivery.body != first.body {
		t.Fatal("manual retry did not retain original event")
	}
	state := readWebhook(t, st, ep, inv, hook.ID)
	if state.Cursor != event.Cursor || state.Attempts != 0 || state.LastError != "" || state.Status != "active" || !state.NextAttempt.IsZero() {
		t.Fatalf("successful retry did not clear failure state: %+v", state)
	}
}

func TestServiceWebhookProductionClientRejectsNonPublicDestinations(t *testing.T) {
	var reached atomic.Bool
	receiver := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { reached.Store(true) }))
	defer receiver.Close()
	client := serviceWebhookClient()
	defer client.CloseIdleConnections()
	for _, destination := range []string{receiver.URL, "https://10.0.0.1", "https://169.254.169.254", "https://100.64.0.1", "https://198.18.0.1", "https://[::1]", "https://[::ffff:127.0.0.1]"} {
		t.Run(destination, func(t *testing.T) {
			request, err := http.NewRequestWithContext(t.Context(), http.MethodPost, destination, nil)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = client.Do(request); err == nil || !strings.Contains(err.Error(), "not public") {
				t.Fatalf("private destination was not blocked: %v", err)
			}
		})
	}
	if reached.Load() {
		t.Fatal("production client reached the loopback fixture")
	}
}

func TestServiceWebhookPostgresReplicaRestartAndUncertainDelivery(t *testing.T) {
	cfg := acceptancePostgresConfig(t)
	open := func() store.Store {
		t.Helper()
		st, err := store.Open(t.Context(), cfg)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = st.Close() })
		return st
	}
	firstStore, secondStore := open(), open()
	deliveries := make(chan webhookDelivery, 8)
	receiver := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		deliveries <- webhookDelivery{string(raw), r.Header.Get("X-AgentScope-Signature"), r.Header.Get("X-AgentScope-Event-ID")}
		w.WriteHeader(204)
	}))
	defer receiver.Close()
	client := receiver.Client()
	defer client.CloseIdleConnections()
	s, inv, ep, hook, secret := webhookFixture(t, firstStore, receiver.URL)
	if _, err := serviceJournal(firstStore, ep, inv).Append(t.Context(), "one", "invocation.completed", map[string]any{"status": "completed"}); err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().Truncate(time.Second)
	clock := func() time.Time { return now }
	// Receiver accepted, but the local cursor commit fails. This is deliberately
	// at-least-once: restart must redeliver the same event, with a fresh signature.
	s.store = failServiceStore{Store: firstStore, kv: &failServiceKV{KVRepository: firstStore.KV(), key: hook.ID.String()}}
	if err := s.deliverServiceWebhooksWith(t.Context(), inv, ep, client, clock); err == nil {
		t.Fatal("cursor commit failure injection did not run")
	}
	first := receivedWebhook(t, deliveries)
	verifyWebhookSignature(t, first, secret, now)
	_ = firstStore.Close()
	now = now.Add(time.Second)
	thirdStore := open()
	servers := []*Server{newLegacyEndpointTestServer(ServerOptions{Store: secondStore, AuthToken: "webhook-fixture"}), newLegacyEndpointTestServer(ServerOptions{Store: thirdStore, AuthToken: "webhook-fixture"})}
	var wg sync.WaitGroup
	for _, server := range servers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := server.deliverServiceWebhooksWith(t.Context(), inv, ep, client, clock); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if len(deliveries) != 1 {
		t.Fatalf("replica lock allowed duplicate simultaneous deliveries: %d", len(deliveries))
	}
	retried := <-deliveries
	verifyWebhookSignature(t, retried, secret, now)
	if retried.eventID != first.eventID || retried.body != first.body || retried.signature == first.signature {
		t.Fatal("restart changed event identity/body or kept stale signature")
	}
	state := readWebhook(t, thirdStore, ep, inv, hook.ID)
	if state.Cursor == "" || state.Attempts != 0 {
		t.Fatalf("restart did not commit cursor: %+v", state)
	}
	row, err := thirdStore.KV().Get(t.Context(), ep.Tenant, webhookPath(inv.ID), hook.ID.String())
	if err != nil || bytes.Contains(row.Value, []byte(secret)) {
		t.Fatalf("signing secret leaked into persistence: %v", err)
	}
	t.Logf("verified durable redelivery %s", first.eventID)
}
