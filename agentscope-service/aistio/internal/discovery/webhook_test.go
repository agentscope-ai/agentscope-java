package discovery

import (
	"testing"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

func TestValidate(t *testing.T) {
	v := &AgentValidator{}
	replicas := int32(1)

	cases := []struct {
		name    string
		agent   *v1alpha1.Agent
		wantErr bool
	}{
		{
			name: "valid declarative",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:        v1alpha1.AgentTypeDeclarative,
				Runtime:     "agentscope-java",
				Declarative: &v1alpha1.DeclarativeSpec{},
			}},
		},
		{
			name: "declarative missing spec",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:    v1alpha1.AgentTypeDeclarative,
				Runtime: "agentscope-java",
			}},
			wantErr: true,
		},
		{
			name: "declarative with byo set",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:        v1alpha1.AgentTypeDeclarative,
				Runtime:     "agentscope-java",
				Declarative: &v1alpha1.DeclarativeSpec{},
				BYO:         &v1alpha1.BYOSpec{Image: "x"},
			}},
			wantErr: true,
		},
		{
			name: "declarative unknown runtime",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:        v1alpha1.AgentTypeDeclarative,
				Runtime:     "not-real",
				Declarative: &v1alpha1.DeclarativeSpec{},
			}},
			wantErr: true,
		},
		{
			name: "valid byo image",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:    v1alpha1.AgentTypeBYO,
				Runtime: "agentscope-java",
				BYO:     &v1alpha1.BYOSpec{Image: "example.com/a:1"},
			}},
		},
		{
			name: "valid byo workloadRef",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:    v1alpha1.AgentTypeBYO,
				Runtime: "custom",
				BYO:     &v1alpha1.BYOSpec{WorkloadRef: &v1alpha1.ObjectReference{Kind: "Deployment", Name: "d"}},
			}},
		},
		{
			name: "byo image and workloadRef mutually exclusive",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:    v1alpha1.AgentTypeBYO,
				Runtime: "agentscope-java",
				BYO: &v1alpha1.BYOSpec{
					Image:       "x",
					WorkloadRef: &v1alpha1.ObjectReference{Name: "d"},
				},
			}},
			wantErr: true,
		},
		{
			name: "byo missing both",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:    v1alpha1.AgentTypeBYO,
				Runtime: "agentscope-java",
				BYO:     &v1alpha1.BYOSpec{},
			}},
			wantErr: true,
		},
		{
			name: "byo workloadRef with replicas rejected",
			agent: &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{
				Type:    v1alpha1.AgentTypeBYO,
				Runtime: "custom",
				BYO: &v1alpha1.BYOSpec{
					WorkloadRef: &v1alpha1.ObjectReference{Name: "d"},
					Replicas:    &replicas,
				},
			}},
			wantErr: true,
		},
		{
			name:    "unknown type",
			agent:   &v1alpha1.Agent{Spec: v1alpha1.AgentSpec{Type: "Weird"}},
			wantErr: true,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := v.validate(tc.agent)
			if tc.wantErr && err == nil {
				t.Error("expected error, got nil")
			}
			if !tc.wantErr && err != nil {
				t.Errorf("unexpected error: %v", err)
			}
		})
	}
}
