package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestParseSSE(t *testing.T) {
	stream := strings.Join([]string{
		": heartbeat",
		"event: message",
		`data: {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"search"}]}}`,
		"",
	}, "\n")

	resp, err := parseSSE(strings.NewReader(stream))
	if err != nil {
		t.Fatalf("parseSSE: %v", err)
	}
	if len(resp.Result) == 0 {
		t.Fatalf("expected a result payload, got %+v", resp)
	}
}

func TestDiscoverTools_StdioReturnsNil(t *testing.T) {
	tools, err := DiscoverTools(context.Background(), "Stdio", "", nil, time.Second)
	if err != nil || tools != nil {
		t.Fatalf("stdio discovery should be a no-op, got tools=%v err=%v", tools, err)
	}
}

func TestDiscoverTools_JSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Accept"); !strings.Contains(got, "text/event-stream") {
			t.Errorf("Accept header missing event-stream: %q", got)
		}
		var req struct {
			Method string `json:"method"`
		}
		_ = decodeJSON(r, &req)
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Mcp-Session-Id", "sess-1")
		switch req.Method {
		case "initialize":
			fmt.Fprint(w, `{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26"}}`)
		case "tools/list":
			fmt.Fprint(w, `{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search","description":"web"},{"name":"fetch"}]}}`)
		default:
			w.WriteHeader(http.StatusAccepted)
		}
	}))
	defer srv.Close()

	tools, err := DiscoverTools(context.Background(), "Remote", srv.URL, map[string]string{"Authorization": "Bearer x"}, 2*time.Second)
	if err != nil {
		t.Fatalf("DiscoverTools: %v", err)
	}
	if len(tools) != 2 || tools[0].Name != "search" || tools[1].Name != "fetch" {
		t.Fatalf("unexpected tools: %+v", tools)
	}
}

func TestDiscoverTools_SSE(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Method string `json:"method"`
		}
		_ = decodeJSON(r, &req)
		if req.Method == "" {
			// notifications/initialized has no id/method we assert on here.
			w.WriteHeader(http.StatusAccepted)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		switch req.Method {
		case "initialize":
			fmt.Fprint(w, "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n")
		case "tools/list":
			fmt.Fprint(w, "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"sse-tool\"}]}}\n\n")
		default:
			w.WriteHeader(http.StatusAccepted)
		}
	}))
	defer srv.Close()

	tools, err := DiscoverTools(context.Background(), "Remote", srv.URL, nil, 2*time.Second)
	if err != nil {
		t.Fatalf("DiscoverTools(SSE): %v", err)
	}
	if len(tools) != 1 || tools[0].Name != "sse-tool" {
		t.Fatalf("unexpected SSE tools: %+v", tools)
	}
}

// decodeJSON is a tiny helper to read a JSON body in tests.
func decodeJSON(r *http.Request, v interface{}) error {
	return json.NewDecoder(r.Body).Decode(v)
}
