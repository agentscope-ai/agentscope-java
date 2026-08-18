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

//go:build e2e

package e2e

import (
	"context"
	"os"
	"testing"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/clientcmd"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

func init() {
	_ = v1alpha1.AddToScheme(scheme.Scheme)
}

func TestAgentCRDExists(t *testing.T) {
	c := getClient(t)
	var agents v1alpha1.AgentList
	if err := c.List(context.Background(), &agents); err != nil {
		t.Fatalf("failed to list agents (CRD may not be installed): %v", err)
	}
	t.Logf("found %d agents", len(agents.Items))
}

func TestAgentTeamCRDExists(t *testing.T) {
	c := getClient(t)
	var teams v1alpha1.AgentTeamList
	if err := c.List(context.Background(), &teams); err != nil {
		t.Fatalf("failed to list agent teams (CRD may not be installed): %v", err)
	}
	t.Logf("found %d agent teams", len(teams.Items))
}

func TestCreateDeclarativeAgent(t *testing.T) {
	c := getClient(t)
	ctx := context.Background()

	agent := &v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "e2e-test-agent",
			Namespace: "default",
		},
		Spec: v1alpha1.AgentSpec{
			Type:    v1alpha1.AgentTypeDeclarative,
			Runtime: "agentscope-java",
			Declarative: &v1alpha1.DeclarativeSpec{
				AgentConfig: v1alpha1.AgentConfig{
					SystemMessage: "e2e test agent",
				},
			},
		},
	}

	if err := c.Create(ctx, agent); err != nil {
		t.Fatalf("failed to create agent: %v", err)
	}
	defer func() {
		_ = c.Delete(ctx, agent)
	}()

	// Wait for reconcile
	time.Sleep(5 * time.Second)

	var fetched v1alpha1.Agent
	if err := c.Get(ctx, client.ObjectKeyFromObject(agent), &fetched); err != nil {
		t.Fatalf("failed to get agent: %v", err)
	}

	if fetched.Status.ManagementMode != v1alpha1.ManagementModeCPManaged {
		t.Errorf("expected CP-Managed, got %s", fetched.Status.ManagementMode)
	}
}

func TestGRPCConnectivity(t *testing.T) {
	// Test that the ASDP gRPC endpoint is reachable when experimental is enabled.
	// This is a placeholder for when e2e runs in a kind cluster with the
	// control plane started with --enable-experimental.
	t.Skip("requires running control plane with --enable-experimental")
}

func getClient(t *testing.T) client.Client {
	t.Helper()
	kubeconfig := os.Getenv("KUBECONFIG")
	if kubeconfig == "" {
		kubeconfig = os.Getenv("HOME") + "/.kube/config"
	}
	cfg, err := clientcmd.BuildConfigFromFlags("", kubeconfig)
	if err != nil {
		t.Skipf("no kubeconfig available: %v", err)
	}
	c, err := client.New(cfg, client.Options{Scheme: scheme.Scheme})
	if err != nil {
		t.Fatalf("failed to create client: %v", err)
	}
	return c
}
