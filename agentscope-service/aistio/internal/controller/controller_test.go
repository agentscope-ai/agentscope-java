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

package controller

import (
	"context"
	"encoding/json"
	"testing"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/scheme"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/event"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

func init() {
	_ = v1alpha1.AddToScheme(scheme.Scheme)
}

func newScheme() *runtime.Scheme {
	s := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(s)
	return s
}

func TestAgentFinalizerPresent(t *testing.T) {
	s := newScheme()

	agent := &v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{
			Name:       "test-agent",
			Namespace:  "default",
			Finalizers: []string{agentFinalizer},
		},
		Spec: v1alpha1.AgentSpec{
			Type:    v1alpha1.AgentTypeDeclarative,
			Runtime: "agentscope-java",
		},
	}

	c := fake.NewClientBuilder().WithScheme(s).WithObjects(agent).Build()

	var fetched v1alpha1.Agent
	if err := c.Get(context.Background(), client.ObjectKeyFromObject(agent), &fetched); err != nil {
		t.Fatalf("failed to get agent: %v", err)
	}

	found := false
	for _, f := range fetched.Finalizers {
		if f == agentFinalizer {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected agent to have the agentscope finalizer")
	}
}

func TestSetConditionInList(t *testing.T) {
	conditions := []v1alpha1.Condition{
		{Type: v1alpha1.ConditionReady, Status: metav1.ConditionFalse, Reason: "NotReady"},
	}

	// Update existing condition
	setConditionInList(&conditions, v1alpha1.Condition{
		Type:   v1alpha1.ConditionReady,
		Status: metav1.ConditionTrue,
		Reason: "Ready",
	})

	if len(conditions) != 1 {
		t.Errorf("expected 1 condition after update, got %d", len(conditions))
	}
	if conditions[0].Status != metav1.ConditionTrue {
		t.Errorf("expected condition status True, got %s", conditions[0].Status)
	}

	// Append new condition
	setConditionInList(&conditions, v1alpha1.Condition{
		Type:   v1alpha1.ConditionAccepted,
		Status: metav1.ConditionTrue,
		Reason: "Accepted",
	})

	if len(conditions) != 2 {
		t.Errorf("expected 2 conditions after append, got %d", len(conditions))
	}
}

func TestToDataPlaneInfo(t *testing.T) {
	info := &prober.DataPlaneInfo{
		ContractLevel:   3,
		SDKVersion:      "2.1.0",
		Version:         "1.0.0",
		SessionAffinity: "instance",
		Capabilities:    []string{"session-reporting", "context-mgmt"},
		AgentConfig: &prober.ProbeAgentConfig{
			Model:         "qwen-max",
			ModelProvider: "DashScope",
			Tools:         json.RawMessage(`["search"]`),
		},
	}

	dpi := toDataPlaneInfo(info)

	if dpi.ContractLevel != 3 {
		t.Errorf("expected contractLevel 3, got %d", dpi.ContractLevel)
	}
	if dpi.SDKVersion != "2.1.0" {
		t.Errorf("expected sdkVersion 2.1.0, got %s", dpi.SDKVersion)
	}
	if dpi.Model != "qwen-max" {
		t.Errorf("expected model qwen-max, got %s", dpi.Model)
	}
	if dpi.ModelProvider != "DashScope" {
		t.Errorf("expected modelProvider DashScope, got %s", dpi.ModelProvider)
	}
	if len(dpi.Tools) != 1 || dpi.Tools[0] != "search" {
		t.Errorf("expected tools [search], got %v", dpi.Tools)
	}
	if dpi.LastProbeAt == "" {
		t.Error("expected LastProbeAt to be set")
	}
}

func TestToDataPlaneInfoNilAgentConfig(t *testing.T) {
	info := &prober.DataPlaneInfo{
		ContractLevel: 1,
	}

	dpi := toDataPlaneInfo(info)

	if dpi.ContractLevel != 1 {
		t.Errorf("expected contractLevel 1, got %d", dpi.ContractLevel)
	}
	if dpi.Model != "" {
		t.Errorf("expected empty model, got %s", dpi.Model)
	}
}

func TestAgentWorkloadRefPredicate(t *testing.T) {
	declarativeAgent := &v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{Name: "decl", Namespace: "default"},
		Spec: v1alpha1.AgentSpec{
			Type:    v1alpha1.AgentTypeDeclarative,
			Runtime: "agentscope-java",
		},
	}

	byoImageAgent := &v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{Name: "byo-img", Namespace: "default"},
		Spec: v1alpha1.AgentSpec{
			Type:    v1alpha1.AgentTypeBYO,
			Runtime: "custom",
			BYO:     &v1alpha1.BYOSpec{Image: "my-image:latest"},
		},
	}

	byoWorkloadRefAgent := &v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{Name: "byo-wl", Namespace: "default"},
		Spec: v1alpha1.AgentSpec{
			Type:    v1alpha1.AgentTypeBYO,
			Runtime: "custom",
			BYO: &v1alpha1.BYOSpec{
				WorkloadRef: &v1alpha1.ObjectReference{Name: "existing-deploy"},
			},
		},
	}

	// wantWorkloadRef=false should match declarative and BYO-image, not workloadRef
	pred := agentWorkloadRefPredicate(false)
	if !pred.Generic(event.TypedGenericEvent[client.Object]{Object: declarativeAgent}) {
		t.Error("expected declarative agent to match wantWorkloadRef=false")
	}
	if !pred.Generic(event.TypedGenericEvent[client.Object]{Object: byoImageAgent}) {
		t.Error("expected BYO image agent to match wantWorkloadRef=false")
	}
	if pred.Generic(event.TypedGenericEvent[client.Object]{Object: byoWorkloadRefAgent}) {
		t.Error("expected BYO workloadRef agent to NOT match wantWorkloadRef=false")
	}

	// wantWorkloadRef=true should match only workloadRef
	pred = agentWorkloadRefPredicate(true)
	if pred.Generic(event.TypedGenericEvent[client.Object]{Object: declarativeAgent}) {
		t.Error("expected declarative agent to NOT match wantWorkloadRef=true")
	}
	if pred.Generic(event.TypedGenericEvent[client.Object]{Object: byoImageAgent}) {
		t.Error("expected BYO image agent to NOT match wantWorkloadRef=true")
	}
	if !pred.Generic(event.TypedGenericEvent[client.Object]{Object: byoWorkloadRefAgent}) {
		t.Error("expected BYO workloadRef agent to match wantWorkloadRef=true")
	}
}
