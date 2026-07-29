package adapter

import (
	"testing"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

func declAgentWithExtras() *v1alpha1.Agent {
	return &v1alpha1.Agent{
		Spec: v1alpha1.AgentSpec{
			Runtime: "agentscope-java",
			Type:    v1alpha1.AgentTypeDeclarative,
			Declarative: &v1alpha1.DeclarativeSpec{
				AgentConfig: v1alpha1.AgentConfig{
					SystemMessage:  "hello",
					ModelConfigRef: "m",
				},
				Tools: []v1alpha1.ToolBinding{
					{Type: "McpServer", MCPServer: &v1alpha1.MCPServerRef{
						Name: "tavily", ToolNames: []string{"search"}, RequireApproval: []string{"search"},
					}},
				},
				Skills: &v1alpha1.SkillsSpec{
					Refs:     []string{"oci://reg/skill:1"},
					Bindings: []v1alpha1.SkillBinding{{Name: "summarize", Instructions: "do it"}},
				},
				Subagents: []v1alpha1.SubagentSpec{
					{Name: "researcher", Model: "qwen", Steps: 3},
				},
				TeamTemplates: []v1alpha1.TeamTemplateRef{
					{Name: "review", Members: []v1alpha1.TeamTemplateMember{{Role: "lead", AgentRef: "a"}}},
				},
			},
		},
	}
}

func TestRenderAgentConfig_IncludesAllSections(t *testing.T) {
	agent := declAgentWithExtras()
	cfg := RenderAgentConfig(agent, nil)

	if cfg["systemMessage"] != "hello" || cfg["modelConfigRef"] != "m" {
		t.Errorf("core fields missing: %+v", cfg)
	}
	if _, ok := cfg["tools"]; !ok {
		t.Error("tools missing (should fall back to declarative bindings)")
	}
	if _, ok := cfg["subagents"]; !ok {
		t.Error("subagents missing")
	}
	if _, ok := cfg["teamTemplates"]; !ok {
		t.Error("teamTemplates missing")
	}
	// Skills are NOT part of the agent config (delivered separately).
	if _, ok := cfg["skills"]; ok {
		t.Error("skills should not be embedded in RenderAgentConfig")
	}

	tools := cfg["tools"].([]map[string]interface{})
	if len(tools) != 1 || tools[0]["mcpServer"] != "tavily" {
		t.Errorf("tool binding not rendered: %+v", tools)
	}
}

func TestRenderAgentConfig_PrefersResolvedTools(t *testing.T) {
	agent := declAgentWithExtras()
	cfg := RenderAgentConfig(agent, []ToolConfig{
		{Name: "search", MCPServer: "tavily", URL: "https://tavily", ToolNames: []string{"search"}},
	})
	tools := cfg["tools"].([]map[string]interface{})
	if len(tools) != 1 || tools[0]["url"] != "https://tavily" {
		t.Errorf("resolved tools (with URL) not preferred: %+v", tools)
	}
}

func TestRenderSkills(t *testing.T) {
	agent := declAgentWithExtras()
	skills := RenderSkills(agent)
	if len(skills) != 2 {
		t.Fatalf("expected 2 skills (1 ref + 1 binding), got %d: %+v", len(skills), skills)
	}
	if skills[0]["type"] != "oci" || skills[1]["type"] != "inline" {
		t.Errorf("skill types wrong: %+v", skills)
	}

	// No skills -> nil.
	if got := RenderSkills(&v1alpha1.Agent{Spec: v1alpha1.AgentSpec{Declarative: &v1alpha1.DeclarativeSpec{}}}); got != nil {
		t.Errorf("expected nil skills, got %+v", got)
	}
}
