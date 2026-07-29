package mcp

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"strings"
	"sync/atomic"
	"time"
)

// protocolVersion is the MCP protocol revision the control plane advertises.
const protocolVersion = "2025-03-26"

// Tool represents a tool advertised by an MCP server.
type Tool struct {
	Name        string `json:"name"`
	Description string `json:"description,omitempty"`
}

// jsonRPCRequest is a JSON-RPC 2.0 request envelope.
type jsonRPCRequest struct {
	JSONRPC string      `json:"jsonrpc"`
	ID      int64       `json:"id"`
	Method  string      `json:"method"`
	Params  interface{} `json:"params,omitempty"`
}

// jsonRPCNotification is a JSON-RPC 2.0 notification (no id, no response).
type jsonRPCNotification struct {
	JSONRPC string      `json:"jsonrpc"`
	Method  string      `json:"method"`
	Params  interface{} `json:"params,omitempty"`
}

// jsonRPCResponse is a JSON-RPC 2.0 response envelope.
type jsonRPCResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *jsonRPCError   `json:"error,omitempty"`
}

type jsonRPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// initializeParams matches the MCP initialize request parameters.
type initializeParams struct {
	ProtocolVersion string          `json:"protocolVersion"`
	Capabilities    json.RawMessage `json:"capabilities"`
	ClientInfo      clientInfo      `json:"clientInfo"`
}

type clientInfo struct {
	Name    string `json:"name"`
	Version string `json:"version"`
}

// toolsListResult represents the result of tools/list.
type toolsListResult struct {
	Tools []Tool `json:"tools"`
}

var requestID atomic.Int64

// client is a minimal MCP Streamable HTTP client. It carries the session id
// returned by the server during initialize so that subsequent requests are
// associated with the same session.
type client struct {
	httpClient *http.Client
	url        string
	headers    map[string]string
	sessionID  string
}

// DiscoverTools connects to an MCP server and returns its advertised tools.
// For Remote servers it speaks JSON-RPC 2.0 over Streamable HTTP, handling both
// plain application/json and text/event-stream responses, session ids
// (Mcp-Session-Id) and the post-initialize notifications/initialized handshake.
// For Stdio servers, discovery is not supported from the controller (returns nil, nil).
func DiscoverTools(ctx context.Context, serverType, url string, headers map[string]string, timeout time.Duration) ([]Tool, error) {
	if serverType != "Remote" {
		return nil, nil
	}
	if url == "" {
		return nil, fmt.Errorf("url is required for Remote MCP server")
	}

	cl := &client{
		httpClient: &http.Client{Timeout: timeout},
		url:        url,
		headers:    headers,
	}

	if err := cl.initialize(ctx); err != nil {
		return nil, fmt.Errorf("initialize: %w", err)
	}

	// Best-effort: signal the server that initialization is complete. Servers
	// that don't require it simply ignore the notification.
	cl.notifyInitialized(ctx)

	tools, err := cl.listTools(ctx)
	if err != nil {
		return nil, fmt.Errorf("tools/list: %w", err)
	}
	return tools, nil
}

func (c *client) initialize(ctx context.Context) error {
	params := initializeParams{
		ProtocolVersion: protocolVersion,
		Capabilities:    json.RawMessage(`{}`),
		ClientInfo: clientInfo{
			Name:    "aistio",
			Version: "0.2.0",
		},
	}
	resp, err := c.doRPC(ctx, "initialize", params)
	if err != nil {
		return err
	}
	if resp.Error != nil {
		return fmt.Errorf("server error %d: %s", resp.Error.Code, resp.Error.Message)
	}
	return nil
}

func (c *client) listTools(ctx context.Context) ([]Tool, error) {
	resp, err := c.doRPC(ctx, "tools/list", struct{}{})
	if err != nil {
		return nil, err
	}
	if resp.Error != nil {
		return nil, fmt.Errorf("server error %d: %s", resp.Error.Code, resp.Error.Message)
	}
	var result toolsListResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		return nil, fmt.Errorf("parsing tools/list result: %w", err)
	}
	return result.Tools, nil
}

// notifyInitialized sends the notifications/initialized message. Errors are
// ignored because not all servers require or acknowledge it.
func (c *client) notifyInitialized(ctx context.Context) {
	body, err := json.Marshal(jsonRPCNotification{
		JSONRPC: "2.0",
		Method:  "notifications/initialized",
	})
	if err != nil {
		return
	}
	req, err := c.newRequest(ctx, body)
	if err != nil {
		return
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return
	}
	_ = resp.Body.Close()
}

func (c *client) doRPC(ctx context.Context, method string, params interface{}) (*jsonRPCResponse, error) {
	body, err := json.Marshal(jsonRPCRequest{
		JSONRPC: "2.0",
		ID:      requestID.Add(1),
		Method:  method,
		Params:  params,
	})
	if err != nil {
		return nil, fmt.Errorf("marshaling request: %w", err)
	}

	req, err := c.newRequest(ctx, body)
	if err != nil {
		return nil, err
	}

	httpResp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("sending request: %w", err)
	}
	defer httpResp.Body.Close()

	// Capture the session id from the initialize response for reuse.
	if sid := httpResp.Header.Get("Mcp-Session-Id"); sid != "" {
		c.sessionID = sid
	}

	if httpResp.StatusCode != http.StatusOK {
		snippet, _ := io.ReadAll(io.LimitReader(httpResp.Body, 512))
		return nil, fmt.Errorf("unexpected status %d: %s", httpResp.StatusCode, string(snippet))
	}

	mediaType, _, _ := mime.ParseMediaType(httpResp.Header.Get("Content-Type"))
	if mediaType == "text/event-stream" {
		return parseSSE(httpResp.Body)
	}

	var rpcResp jsonRPCResponse
	if err := json.NewDecoder(httpResp.Body).Decode(&rpcResp); err != nil {
		return nil, fmt.Errorf("decoding response: %w", err)
	}
	return &rpcResp, nil
}

// newRequest builds a POST request with the headers required by the Streamable
// HTTP transport (Accept for both json and SSE, optional session id, plus any
// caller-supplied auth headers).
func (c *client) newRequest(ctx context.Context, body []byte) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("creating request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	if c.sessionID != "" {
		req.Header.Set("Mcp-Session-Id", c.sessionID)
	}
	for k, v := range c.headers {
		req.Header.Set(k, v)
	}
	return req, nil
}

// parseSSE reads a text/event-stream body and returns the first JSON-RPC
// message that carries a result or error.
func parseSSE(r io.Reader) (*jsonRPCResponse, error) {
	scanner := bufio.NewScanner(r)
	// Allow large SSE data frames (tool lists can be sizable).
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)

	var data strings.Builder
	flush := func() (*jsonRPCResponse, bool) {
		if data.Len() == 0 {
			return nil, false
		}
		var resp jsonRPCResponse
		if err := json.Unmarshal([]byte(data.String()), &resp); err == nil &&
			(len(resp.Result) > 0 || resp.Error != nil) {
			return &resp, true
		}
		data.Reset()
		return nil, false
	}

	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			// End of an event: try to decode the accumulated data.
			if resp, ok := flush(); ok {
				return resp, nil
			}
			continue
		}
		if strings.HasPrefix(line, ":") {
			continue // comment/heartbeat
		}
		if payload, ok := strings.CutPrefix(line, "data:"); ok {
			data.WriteString(strings.TrimPrefix(payload, " "))
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("reading event stream: %w", err)
	}
	// Stream ended; attempt a final decode of any trailing data.
	if resp, ok := flush(); ok {
		return resp, nil
	}
	return nil, fmt.Errorf("no JSON-RPC response found in event stream")
}
