// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
)

func TestManagedRuntimeUploadRetainsBinaryContentAndBoundContext(t *testing.T) {
	origin := runtimeCall().Context
	content := bytes.Repeat([]byte{0, 1, 255, 42}, 768*1024)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !r.Close {
			t.Error("streaming upload connection must not return to the HTTP/1 pool")
		}
		if r.URL.EscapedPath() != "/api/internal/managed/sessions/session%20one/files" || r.Header.Get("X-Builder-Internal-User") != "resource-owner" || r.Header.Get("X-File-Name") != "%E6%96%87%E4%BB%B6+name.bin" {
			t.Errorf("unexpected binary transport: %s %v", r.URL, r.Header)
		}
		raw, err := base64.RawURLEncoding.DecodeString(r.Header.Get("X-Managed-Runtime-Context"))
		var actual runtimeprotocol.Context
		if err != nil || json.Unmarshal(raw, &actual) != nil || actual.Actor["user"] != "alice" || actual.CommandID != origin.CommandID || actual.Namespace != origin.Namespace {
			t.Errorf("origin changed: %s %v", raw, err)
		}
		data, err := io.ReadAll(r.Body)
		if err != nil || !bytes.Equal(content, data) {
			t.Errorf("binary bytes changed: %d %v", len(data), err)
		}
		_, _ = w.Write([]byte(`{"file_id":"file","size":3145728}`))
	}))
	defer server.Close()
	client := &Server{cfg: Config{SessionPortalURL: server.URL, DataURL: server.URL + "/wrong", InternalToken: "internal-test"}}
	response, err := client.ManagedRuntimeUpload(t.Context(), "resource-owner", origin, "文件 name.bin", "application/octet-stream", bytes.NewReader(content))
	if err != nil || !bytes.Contains(response, []byte(`"file_id":"file"`)) {
		t.Fatalf("upload failed: %s %v", response, err)
	}
	origin.Actor["large"] = strings.Repeat("x", 4096)
	if _, err = client.ManagedRuntimeUpload(t.Context(), "resource-owner", origin, "file", "", bytes.NewReader(content)); err == nil {
		t.Fatal("unbounded context header accepted")
	}
}

func runtimeCall() ManagedRuntimeCall {
	return ManagedRuntimeCall{Context: runtimeprotocol.Context{Version: 1, Tenant: "tenant", Namespace: "namespace", SessionID: "session one", CommandID: "original-key", TraceID: "trace",
		Actor: map[string]any{"user": "alice", "key_hash": "", "internal": false}, PermissionExpiresAt: time.Now().Add(time.Minute).UnixMilli()},
		Operation: "submit_turn", Payload: map[string]any{"input": map[string]any{"message": "task"}}}
}

func TestManagedRuntimeTransportRetainsOriginalScopeActorAndCommand(t *testing.T) {
	call := runtimeCall()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.EscapedPath() != "/api/internal/managed/sessions/session%20one/request" {
			t.Errorf("unexpected runtime path: %s %s", r.Method, r.URL.EscapedPath())
		}
		if r.Header.Get("X-Builder-Internal-Token") != "internal-test" || r.Header.Get("X-Builder-Internal-User") != "resource-owner" || r.Header.Get("X-API-Key") != "" {
			t.Errorf("unexpected transport identity: %v", r.Header)
		}
		var received ManagedRuntimeCall
		if err := json.NewDecoder(r.Body).Decode(&received); err != nil {
			t.Fatal(err)
		}
		if received.Context.Tenant != call.Context.Tenant || received.Context.Namespace != call.Context.Namespace || received.Context.Actor["user"] != "alice" || received.Context.CommandID != "original-key" || received.Operation != "submit_turn" {
			t.Fatalf("original context changed: %+v", received)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"turn","status":"accepted"}`))
	}))
	defer server.Close()
	client := &Server{cfg: Config{SessionPortalURL: server.URL, DataURL: server.URL + "/wrong-dataplane", InternalToken: "internal-test"}}
	result, err := client.ManagedRuntimeRequest(context.Background(), "resource-owner", call)
	if err != nil || string(result) != `{"id":"turn","status":"accepted"}` {
		t.Fatalf("request failed: %s %v", result, err)
	}
}

func TestManagedRuntimeRejectsMissingOriginAndExpiredContextBeforeSending(t *testing.T) {
	count := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { count++; _, _ = w.Write([]byte(`{}`)) }))
	defer server.Close()
	client := &Server{cfg: Config{SessionPortalURL: server.URL, DataURL: server.URL + "/wrong-dataplane"}}
	for _, change := range []func(*ManagedRuntimeCall){
		func(call *ManagedRuntimeCall) { call.Context.PermissionExpiresAt = 1 },
		func(call *ManagedRuntimeCall) { call.Context.Actor = map[string]any{"user": "alice", "internal": true} },
		func(call *ManagedRuntimeCall) { call.Context.Actor = nil },
		func(call *ManagedRuntimeCall) { call.Context.Namespace = "" },
	} {
		call := runtimeCall()
		change(&call)
		if _, err := client.ManagedRuntimeRequest(context.Background(), "resource-owner", call); err == nil {
			t.Fatal("invalid context accepted")
		}
	}
	if count != 0 {
		t.Fatal("invalid origin reached the runtime")
	}
}

func TestManagedRuntimeDoesNotFollowRedirectsAndPreservesStructuredStatus(t *testing.T) {
	forwarded := 0
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { forwarded++; _, _ = w.Write([]byte(`{}`)) }))
	defer target.Close()
	status := http.StatusTemporaryRedirect
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", target.URL)
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{"error":{"code":"admission_limited"}}`))
	}))
	defer server.Close()
	client := &Server{cfg: Config{SessionPortalURL: server.URL, DataURL: server.URL + "/wrong-dataplane", InternalToken: "internal-test"}}
	for _, value := range []int{http.StatusTemporaryRedirect, http.StatusTooManyRequests} {
		status = value
		_, err := client.ManagedRuntimeRequest(context.Background(), "resource-owner", runtimeCall())
		var failure *ManagedServiceError
		if !errors.As(err, &failure) || failure.StatusCode != value {
			t.Fatalf("HTTP status lost: %v", err)
		}
	}
	if forwarded != 0 {
		t.Fatal("internal credentials followed a redirect")
	}
}

// Invoked by the Portal HTTP fixture: real Go HTTP transport reaches the Java role/context gate.
func TestManagedRuntimeLivePortal(t *testing.T) {
	endpoint := os.Getenv("MANAGED_PORTAL_TEST_URL")
	if endpoint == "" {
		t.Skip("Portal-owned loopback fixture is not running")
	}
	var origin runtimeprotocol.Context
	if err := json.Unmarshal([]byte(os.Getenv("MANAGED_PORTAL_TEST_CONTEXT")), &origin); err != nil {
		t.Fatal(err)
	}
	client := &Server{cfg: Config{SessionPortalURL: endpoint, InternalToken: os.Getenv("MANAGED_PORTAL_TEST_TOKEN")}}
	upload := origin
	upload.CommandID = "go-file"
	data := bytes.Repeat([]byte("file-data"), 384*1024)
	file, err := client.ManagedRuntimeUpload(t.Context(), "owner", upload, "Go file.bin", "application/octet-stream", bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	var metadata struct {
		ID   string `json:"file_id"`
		Size int    `json:"size"`
	}
	if json.Unmarshal(file, &metadata) != nil || metadata.ID == "" || metadata.Size != len(data) {
		t.Fatalf("file metadata: %s", file)
	}
	replayed, err := client.ManagedRuntimeUpload(t.Context(), "owner", upload, "Go file.bin", "application/octet-stream", bytes.NewReader(data))
	if err != nil || !bytes.Equal(file, replayed) {
		t.Fatalf("file retry changed: %s %v", replayed, err)
	}
	call := ManagedRuntimeCall{Context: origin, Operation: "submit_turn", Payload: map[string]any{"input": map[string]any{"input": []any{map[string]any{"role": "user", "content": []any{map[string]any{"type": "text", "text": "Go RPC request"}, map[string]any{"type": "file", "file_id": metadata.ID}}}}}}}
	first, err := client.ManagedRuntimeRequest(t.Context(), "owner", call)
	if err != nil {
		t.Fatal(err)
	}
	var turn struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(first, &turn); err != nil || turn.ID == "" {
		t.Fatalf("missing Turn receipt: %s %v", first, err)
	}
	second, err := client.ManagedRuntimeRequest(t.Context(), "owner", call)
	if err != nil || string(first) != string(second) {
		t.Fatalf("Turn retry changed: %s %s %v", first, second, err)
	}
	call.Context.TurnID = &turn.ID
	call.Context.CommandID = "go-input"
	call.Operation = "submit_input"
	call.Payload = map[string]any{"type": "inject", "payload": map[string]any{"message": "Go context"}}
	input, err := client.ManagedRuntimeRequest(t.Context(), "owner", call)
	if err != nil {
		t.Fatal(err)
	}
	var receipt struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	}
	if err := json.Unmarshal(input, &receipt); err != nil || receipt.ID == "" || receipt.Status != "accepted" {
		t.Fatalf("unexpected Input receipt: %s %v", input, err)
	}
	call.Context.CommandID = "go-read"
	call.Operation = "input"
	call.Payload = nil
	call.InputID = receipt.ID
	read, err := client.ManagedRuntimeRequest(t.Context(), "owner", call)
	if err != nil || string(read) != string(input) {
		t.Fatalf("Input query changed: %s %s %v", input, read, err)
	}
}

func TestManagedRuntimeDownloadRetainsBinaryAndRejectsInvalidResponses(t *testing.T) {
	content := bytes.Repeat([]byte{0, 255, 1, 42}, 768*1024)
	origin := runtimeCall().Context
	for _, test := range []struct {
		name   string
		status int
		length string
	}{
		{"binary", 200, strconv.Itoa(len(content))}, {"forbidden", 403, ""},
		{"redirect", 302, ""}, {"oversized", 200, strconv.Itoa((16 << 20) + 1)}, {"unknown-length", 200, ""},
	} {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != "GET" || r.URL.EscapedPath() != "/api/internal/managed/sessions/session%20one/files/file%20one/content" {
					t.Errorf("wrong binary route: %s %s", r.Method, r.URL)
				}
				raw, err := base64.RawURLEncoding.DecodeString(r.Header.Get("X-Managed-Runtime-Context"))
				var actual runtimeprotocol.Context
				if err != nil || json.Unmarshal(raw, &actual) != nil || actual.Actor["user"] != "alice" {
					t.Errorf("original Actor missing: %s %v", raw, err)
				}
				if test.length != "" {
					w.Header().Set("Content-Length", test.length)
				}
				w.WriteHeader(test.status)
				if test.name == "binary" {
					_, _ = w.Write(content)
				} else if test.name == "unknown-length" {
					w.(http.Flusher).Flush()
					_, _ = w.Write([]byte("unknown"))
				}
			}))
			defer server.Close()
			client := &Server{cfg: Config{SessionPortalURL: server.URL, DataURL: server.URL + "/wrong", InternalToken: "test-token"}}
			response, err := client.ManagedRuntimeFileContent(t.Context(), "resource-owner", origin, "file one")
			if test.name != "binary" {
				if err == nil {
					response.Body.Close()
					t.Fatal("invalid download accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			data, err := io.ReadAll(response.Body)
			if err != nil || !bytes.Equal(content, data) {
				t.Fatalf("binary changed: %d %v", len(data), err)
			}
		})
	}
}
