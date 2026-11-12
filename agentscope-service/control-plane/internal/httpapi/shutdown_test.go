// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"context"
	"io"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

func TestServerWaitsForAdmittedRequestBeforeReturningFromShutdown(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	listener.Close()
	server := NewServer(ServerOptions{Addr: address})
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	release, started := make(chan struct{}), make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	defer server.httpServer.Close()
	server.router.GET("/shutdown-fixture", func(c *gin.Context) { close(started); <-release; c.String(200, "committed") })
	stopped := make(chan error, 1)
	go func() { stopped <- server.Start(ctx) }()
	client := &http.Client{Timeout: 3 * time.Second}
	deadline := time.Now().Add(3 * time.Second)
	for {
		response, err := client.Get("http://" + address + "/healthz")
		if err == nil {
			response.Body.Close()
			break
		}
		if time.Now().After(deadline) {
			t.Fatal(err)
		}
		time.Sleep(5 * time.Millisecond)
	}
	requestDone := make(chan error, 1)
	go func() {
		response, err := client.Get("http://" + address + "/shutdown-fixture")
		if err == nil {
			defer response.Body.Close()
			var body []byte
			body, err = io.ReadAll(response.Body)
			if string(body) != "committed" {
				err = io.ErrUnexpectedEOF
			}
		}
		requestDone <- err
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("request not admitted")
	}
	cancel()
	select {
	case err := <-stopped:
		t.Fatalf("server returned before its request drained: %v", err)
	case <-time.After(30 * time.Millisecond):
	}
	close(release)
	select {
	case err := <-requestDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("request did not finish")
	}
	select {
	case err := <-stopped:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("shutdown did not drain")
	}
}
