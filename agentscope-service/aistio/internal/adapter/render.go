package adapter

import "github.com/spring-ai-alibaba/aistio/api/v1alpha1"

// RenderAgentConfig produces the canonical agent runtime configuration delivered
// to the data plane. It is the single source of truth shared by the startup
// ConfigMap (adapter.BuildConfigMap) and the ASDP hot-reload push
// (controller.ConfigPushWatcher), so the two never diverge.
//
// It deliberately excludes deployment-only concerns (replicas, resources, env)
// and skills (delivered separately as CONFIG_TYPE_SKILL). When resolved tools
// are supplied they carry MCP server URLs; otherwise the tool list is derived
// from the agent's declarative tool bindings (names + approval, no URL).
func RenderAgentConfig(agent *v1alpha1.Agent, tools []ToolConfig) map[string]interface{} {
	cfg := map[string]interface{}{
		"name":    agent.Name,
		"runtime": agent.Spec.Runtime,
	}
	if agent.Spec.Declarative == nil {
		return cfg
	}
	decl := agent.Spec.Declarative
	cfg["stream"] = decl.AgentConfig.Stream
	cfg["maxTurns"] = decl.AgentConfig.MaxTurns
	if decl.AgentConfig.SystemMessage != "" {
		cfg["systemMessage"] = decl.AgentConfig.SystemMessage
	}
	if decl.AgentConfig.ModelConfigRef != "" {
		cfg["modelConfigRef"] = decl.AgentConfig.ModelConfigRef
	}

	if toolList := renderTools(agent, tools); len(toolList) > 0 {
		cfg["tools"] = toolList
	}
	if subagents := renderSubagents(decl.Subagents); len(subagents) > 0 {
		cfg["subagents"] = subagents
	}
	if templates := renderTeamTemplates(decl.TeamTemplates); len(templates) > 0 {
		cfg["teamTemplates"] = templates
	}
	return cfg
}

// RenderSkills produces the skill configuration payload (CONFIG_TYPE_SKILL).
// Returns nil when the agent declares no skills.
func RenderSkills(agent *v1alpha1.Agent) []map[string]interface{} {
	if agent.Spec.Declarative == nil || agent.Spec.Declarative.Skills == nil {
		return nil
	}
	s := agent.Spec.Declarative.Skills
	out := make([]map[string]interface{}, 0, len(s.Refs)+len(s.Bindings))
	for _, ref := range s.Refs {
		out = append(out, map[string]interface{}{"type": "oci", "ref": ref})
	}
	for _, b := range s.Bindings {
		entry := map[string]interface{}{"type": "inline", "name": b.Name}
		if b.Description != "" {
			entry["description"] = b.Description
		}
		if b.Instructions != "" {
			entry["instructions"] = b.Instructions
		}
		if b.Ref != "" {
			entry["ref"] = b.Ref
		}
		out = append(out, entry)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// renderTools prefers resolved ToolConfig (with MCP URLs); when none are
// supplied it falls back to the declarative tool bindings on the agent.
func renderTools(agent *v1alpha1.Agent, tools []ToolConfig) []map[string]interface{} {
	if len(tools) > 0 {
		out := make([]map[string]interface{}, 0, len(tools))
		for _, t := range tools {
			tc := map[string]interface{}{"name": t.Name, "mcpServer": t.MCPServer}
			if t.URL != "" {
				tc["url"] = t.URL
			}
			if len(t.ToolNames) > 0 {
				tc["toolNames"] = t.ToolNames
			}
			if len(t.RequireApproval) > 0 {
				tc["requireApproval"] = t.RequireApproval
			}
			out = append(out, tc)
		}
		return out
	}
	if agent.Spec.Declarative == nil {
		return nil
	}
	out := make([]map[string]interface{}, 0, len(agent.Spec.Declarative.Tools))
	for _, b := range agent.Spec.Declarative.Tools {
		if b.MCPServer == nil {
			continue
		}
		tc := map[string]interface{}{"mcpServer": b.MCPServer.Name}
		if len(b.MCPServer.ToolNames) > 0 {
			tc["toolNames"] = b.MCPServer.ToolNames
		}
		if len(b.MCPServer.RequireApproval) > 0 {
			tc["requireApproval"] = b.MCPServer.RequireApproval
		}
		out = append(out, tc)
	}
	return out
}

func renderSubagents(subagents []v1alpha1.SubagentSpec) []map[string]interface{} {
	if len(subagents) == 0 {
		return nil
	}
	out := make([]map[string]interface{}, 0, len(subagents))
	for _, s := range subagents {
		entry := map[string]interface{}{"name": s.Name}
		if s.Description != "" {
			entry["description"] = s.Description
		}
		if s.Model != "" {
			entry["model"] = s.Model
		}
		if s.Instructions != "" {
			entry["instructions"] = s.Instructions
		}
		if len(s.Tools) > 0 {
			entry["tools"] = s.Tools
		}
		if s.Steps > 0 {
			entry["steps"] = s.Steps
		}
		if s.WorkspaceMode != "" {
			entry["workspaceMode"] = s.WorkspaceMode
		}
		if s.URL != "" {
			entry["url"] = s.URL
		}
		out = append(out, entry)
	}
	return out
}

func renderTeamTemplates(templates []v1alpha1.TeamTemplateRef) []map[string]interface{} {
	if len(templates) == 0 {
		return nil
	}
	out := make([]map[string]interface{}, 0, len(templates))
	for _, tt := range templates {
		entry := map[string]interface{}{"name": tt.Name}
		if tt.Description != "" {
			entry["description"] = tt.Description
		}
		if len(tt.Members) > 0 {
			members := make([]map[string]interface{}, 0, len(tt.Members))
			for _, m := range tt.Members {
				members = append(members, map[string]interface{}{
					"role":     m.Role,
					"agentRef": m.AgentRef,
					"prompt":   m.Prompt,
				})
			}
			entry["members"] = members
		}
		out = append(out, entry)
	}
	return out
}
