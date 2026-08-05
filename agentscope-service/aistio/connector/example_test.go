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

package connector_test

import (
	"context"
	"fmt"
	"testing"

	"github.com/spring-ai-alibaba/aistio/connector"
	"github.com/spring-ai-alibaba/aistio/internal/asdp"
)

func ExampleConnector() {
	c := connector.New(connector.Config{
		ControlPlaneAddr: "localhost:15010",
		AgentName:        "my-agent",
		InstanceID:       "pod-0",
		Namespace:        "default",
		Runtime:          "agentscope-go",
		SDKVersion:       "0.3.0",
		Capabilities:     []string{"session-reporting"},
		SessionAffinity:  "none",
		OnConfigPush: func(ct asdp.ConfigType, version string, resources []byte) (bool, string) {
			fmt.Printf("config push: type=%v version=%s\n", ct, version)
			return true, ""
		},
		OnSessionCommand: func(sessionID, command string, params []byte) {
			fmt.Printf("session command: session=%s command=%s\n", sessionID, command)
		},
	})
	_ = c
}

func TestConnectorCreation(t *testing.T) {
	c := connector.New(connector.Config{
		ControlPlaneAddr: "localhost:15010",
		AgentName:        "test-agent",
		InstanceID:       "test-pod",
		Namespace:        "default",
	})
	if c == nil {
		t.Fatal("expected non-nil connector")
	}
}

func TestConnectorStop(t *testing.T) {
	c := connector.New(connector.Config{
		ControlPlaneAddr: "localhost:15010",
		AgentName:        "test-agent",
		InstanceID:       "test-pod",
		Namespace:        "default",
	})
	// Stop before Start should not panic.
	c.Stop()
}

func TestConnectorStartCancelled(t *testing.T) {
	c := connector.New(connector.Config{
		ControlPlaneAddr: "localhost:15010",
		AgentName:        "test-agent",
		InstanceID:       "test-pod",
		Namespace:        "default",
	})

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	err := c.Start(ctx)
	if err == nil {
		t.Fatal("expected error from cancelled context")
	}
}

func TestUpdateSessions(t *testing.T) {
	c := connector.New(connector.Config{
		ControlPlaneAddr: "localhost:15010",
		AgentName:        "test-agent",
		InstanceID:       "test-pod",
		Namespace:        "default",
	})

	sessions := []*asdp.SessionSnapshot{
		{SessionId: "s1", Phase: "running", MessageCount: 5},
		{SessionId: "s2", Phase: "idle", MessageCount: 0},
	}
	c.UpdateSessions(sessions)
}
