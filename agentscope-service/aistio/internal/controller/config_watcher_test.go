package controller

import (
	"context"
	"testing"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

type recordedPush struct {
	namespace  string
	agent      string
	configType int32
	resources  interface{}
}

type fakeDistributor struct {
	pushes  []recordedPush
	forgets int
}

func (f *fakeDistributor) PushConfig(namespace, agentName string, configType int32, resources interface{}) error {
	f.pushes = append(f.pushes, recordedPush{namespace, agentName, configType, resources})
	return nil
}

func (f *fakeDistributor) ForgetAgent(namespace, agentName string) { f.forgets++ }

func TestConfigPushWatcher_PushesCompleteConfig(t *testing.T) {
	dist := &fakeDistributor{}
	w := &ConfigPushWatcher{Dist: dist}

	agent := &v1alpha1.Agent{}
	agent.Name = "chatbot"
	agent.Namespace = "default"
	agent.Spec.Type = v1alpha1.AgentTypeDeclarative
	agent.Spec.Runtime = "agentscope-java"
	agent.Spec.Declarative = &v1alpha1.DeclarativeSpec{
		AgentConfig: v1alpha1.AgentConfig{SystemMessage: "hi", ModelConfigRef: "m"},
		Tools: []v1alpha1.ToolBinding{
			{Type: "McpServer", MCPServer: &v1alpha1.MCPServerRef{Name: "tavily"}},
		},
		Skills: &v1alpha1.SkillsSpec{Refs: []string{"oci://reg/skill:1"}},
		Subagents: []v1alpha1.SubagentSpec{
			{Name: "researcher"},
		},
	}

	w.onAgent(context.Background(), agent)

	// Expect an agent-config push and a skill push.
	var agentPush, skillPush *recordedPush
	for i := range dist.pushes {
		switch dist.pushes[i].configType {
		case DistConfigAgent:
			agentPush = &dist.pushes[i]
		case DistConfigSkill:
			skillPush = &dist.pushes[i]
		}
	}

	if agentPush == nil {
		t.Fatal("expected an agent-config push")
	}
	cfg, ok := agentPush.resources.(map[string]interface{})
	if !ok {
		t.Fatalf("agent push payload type = %T, want map", agentPush.resources)
	}
	for _, key := range []string{"systemMessage", "modelConfigRef", "tools", "subagents"} {
		if _, present := cfg[key]; !present {
			t.Errorf("agent config missing %q: %+v", key, cfg)
		}
	}
	if _, leaked := cfg["skills"]; leaked {
		t.Error("skills must not be embedded in the agent-config push")
	}

	if skillPush == nil {
		t.Fatal("expected a skill-config push")
	}
}

func TestConfigPushWatcher_SkipsNonDeclarative(t *testing.T) {
	dist := &fakeDistributor{}
	w := &ConfigPushWatcher{Dist: dist}

	agent := &v1alpha1.Agent{}
	agent.Spec.Type = v1alpha1.AgentTypeBYO
	w.onAgent(context.Background(), agent)

	if len(dist.pushes) != 0 {
		t.Errorf("expected no pushes for BYO agent, got %d", len(dist.pushes))
	}
}
