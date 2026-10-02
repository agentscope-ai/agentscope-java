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

package mock

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

func TestMockDataPlane_ConfigurableCapabilities(t *testing.T) {
	m := NewMockDataPlane(2)
	defer m.Close()
	m.SetCapabilities([]string{v1alpha1.CapabilitySessionReporting})

	p := prober.NewHTTPProber()
	info, err := p.ProbeInfo(context.Background(), m.Endpoint())
	if err != nil {
		t.Fatalf("ProbeInfo: %v", err)
	}
	if info.ContractLevel != 2 {
		t.Fatalf("expected level 2, got %d", info.ContractLevel)
	}
	if len(info.Capabilities) != 1 || info.Capabilities[0] != v1alpha1.CapabilitySessionReporting {
		t.Fatalf("unexpected capabilities: %v", info.Capabilities)
	}
}

func TestMockDataPlane_Fault501(t *testing.T) {
	m := NewMockDataPlane(3)
	defer m.Close()
	m.SetCapabilities([]string{v1alpha1.CapabilitySessionCommand})
	m.InjectFault501(v1alpha1.CapabilitySessionCommand)

	resp, err := http.Post(m.Endpoint()+"/agentscope/sessions/s1/compress", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotImplemented {
		t.Fatalf("expected 501, got %d", resp.StatusCode)
	}
	if m.CompressCalledFor("s1") {
		t.Fatal("compress must not be recorded on 501")
	}
}

func TestMockDataPlane_Fault409Compress(t *testing.T) {
	m := NewMockDataPlane(3)
	defer m.Close()
	m.SetCapabilities([]string{v1alpha1.CapabilitySessionCommand})
	m.InjectFault409Compress()

	resp, err := http.Post(m.Endpoint()+"/agentscope/sessions/s1/compress", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("expected 409, got %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	var eb map[string]string
	_ = json.Unmarshal(body, &eb)
	if eb["hint"] != "wait_idle" || eb["code"] != "busy" {
		t.Fatalf("expected busy/wait_idle body, got %v", eb)
	}
}

func TestMockDataPlane_MarkStale(t *testing.T) {
	m := NewMockDataPlane(1)
	defer m.Close()

	p := prober.NewHTTPProber()
	ok, err := p.ProbeHealth(context.Background(), m.Endpoint())
	if err != nil || !ok {
		t.Fatalf("expected healthy before MarkStale, ok=%v err=%v", ok, err)
	}

	m.MarkStale()
	ok, err = p.ProbeHealth(context.Background(), m.Endpoint())
	if err != nil {
		t.Fatalf("ProbeHealth: %v", err)
	}
	if ok {
		t.Fatal("expected unhealthy after MarkStale")
	}
}

func TestMockDataPlane_TasksWithoutCapability(t *testing.T) {
	m := NewMockDataPlane(3)
	defer m.Close()
	m.SetCapabilities([]string{v1alpha1.CapabilitySessionCommand})

	resp, err := http.Get(m.Endpoint() + "/agentscope/sessions/s1/tasks")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotImplemented {
		t.Fatalf("expected 501 without task-query, got %d", resp.StatusCode)
	}
}

func TestMockDataPlane_TasksWithCapability(t *testing.T) {
	m := NewMockDataPlane(3)
	defer m.Close()
	m.SetCapabilities([]string{v1alpha1.CapabilityTaskQuery})
	m.SetTasks("s1", []prober.TaskInfo{{ID: "t1", Subject: "do", State: "pending"}})

	p := prober.NewHTTPProber()
	tasks, err := p.FetchTasks(context.Background(), m.Endpoint(), "s1")
	if err != nil {
		t.Fatalf("FetchTasks: %v", err)
	}
	if len(tasks) != 1 || tasks[0].ID != "t1" {
		t.Fatalf("unexpected tasks: %+v", tasks)
	}
}
