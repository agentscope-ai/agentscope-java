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

package asdp_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"testing"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/asdp"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	_ "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
)

func TestHTTPRuntimeMailboxSurvivesReplicaChangeAndRejectsStaleIdentity(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	generation := int64(1)
	makeServer := func() *asdp.Server {
		s, err := asdp.NewServer(asdp.ServerConfig{})
		if err != nil {
			t.Fatal(err)
		}
		s.ConfigureHTTP(st)
		s.SetEventSink(&testEventSink{})
		s.SetIdentityValidator(func(_ context.Context, meta *asdp.UpstreamMeta, credential string, trusted bool) error {
			if credential != "registration-token" || trusted || meta.Generation != generation {
				return fmt.Errorf("rejected")
			}
			return nil
		})
		return s
	}
	first, second := makeServer(), makeServer()
	exchange := func(s *asdp.Server, ack []string, gen int64) *httptest.ResponseRecorder {
		body, _ := json.Marshal(map[string]any{"meta": map[string]any{"tenant": "t", "namespace": "n", "agentId": "a", "bindingId": "b", "agentKey": "agent", "instanceKey": "i", "generation": fmt.Sprint(gen)}, "connect": map[string]any{"runtime": "python", "capabilities": []string{"execute-task"}}, "ack": ack})
		r := httptest.NewRequest("POST", "/api/v1/agent-runtime/exchange", bytes.NewReader(body))
		r.Header.Set("Authorization", "Bearer registration-token")
		w := httptest.NewRecorder()
		s.HTTPHandler().ServeHTTP(w, r)
		return w
	}
	if w := exchange(first, nil, 1); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if err := second.Distributor().SendSessionCommand("t", "n", "a", "i", "session", "interrupt"); err != nil {
		t.Fatal(err)
	}
	var response struct {
		Commands []struct {
			ID string `json:"id"`
		} `json:"commands"`
	}
	w := exchange(first, nil, 1)
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil || len(response.Commands) != 1 {
		t.Fatalf("missing durable command: %s", w.Body)
	}
	commandID := response.Commands[0].ID
	w = exchange(second, []string{commandID}, 1)
	response.Commands = nil
	_ = json.Unmarshal(w.Body.Bytes(), &response)
	if w.Code != 200 || len(response.Commands) != 0 {
		t.Fatalf("ack not shared: %s", w.Body)
	}
	generation = 2
	if w = exchange(first, nil, 1); w.Code != 401 {
		t.Fatalf("stale generation accepted: %s", w.Body)
	}
	if w = exchange(second, nil, 2); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
}

type lifecycleFailureSink struct {
	testEventSink
	failure error
}

func (s *lifecycleFailureSink) HandleExecutionAttemptReport(tenant, namespace, agentID, bindingID, instanceKey string, generation int64, report *asdp.ExecutionAttemptReport) error {
	return s.failure
}
func (s *lifecycleFailureSink) HandleConversationTurnReport(identity asdp.ReportIdentity, report *asdp.ConversationTurnReport) error {
	return s.failure
}

func TestHTTPRuntimeLifecycleReportsRetryStorageFailures(t *testing.T) {
	st, _ := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	defer st.Close()
	s, err := asdp.NewServer(asdp.ServerConfig{})
	if err != nil {
		t.Fatal(err)
	}
	sink := &lifecycleFailureSink{}
	s.ConfigureHTTP(st)
	s.SetEventSink(sink)
	s.SetIdentityValidator(func(context.Context, *asdp.UpstreamMeta, string, bool) error { return nil })
	for _, tc := range []struct {
		name     string
		failure  error
		status   int
		rejected int
	}{
		{"database unavailable", fmt.Errorf("database unavailable"), 503, 0},
		{"CAS conflict retries", store.ErrConflict, 503, 0},
		{"fenced report is rejected", store.ErrForbidden, 200, 2},
		{"absent attempt is rejected", store.ErrNotFound, 200, 2},
		{"committed reports", nil, 200, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			sink.failure = tc.failure
			body := `{"meta":{"tenant":"t","namespace":"n","agentId":"a","bindingId":"b","agentKey":"agent","instanceKey":"i","generation":"1"},"connect":{},"messages":[{"heartbeat":{}},{"executionAttempt":{"action":"complete"}},{"conversationTurn":{"action":"completed"}}]}`
			w := httptest.NewRecorder()
			s.HTTPHandler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/agent-runtime/exchange", bytes.NewBufferString(body)))
			if w.Code != tc.status {
				t.Fatalf("status=%d body=%s", w.Code, w.Body)
			}
			if w.Code == 200 {
				var response struct {
					Rejected []struct {
						Index int `json:"index"`
					} `json:"rejected_reports"`
				}
				if json.Unmarshal(w.Body.Bytes(), &response) != nil || len(response.Rejected) != tc.rejected {
					t.Fatalf("rejection response: %s", w.Body)
				}
				for i, r := range response.Rejected {
					if r.Index != i+1 {
						t.Fatalf("lost original report index: %s", w.Body)
					}
				}
			}
		})
	}
}
