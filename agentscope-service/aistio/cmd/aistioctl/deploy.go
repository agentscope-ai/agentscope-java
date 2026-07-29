package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"
	"gopkg.in/yaml.v3"
)

// projectConfig mirrors the agentscope.yaml layout.
type projectConfig struct {
	Name         string            `yaml:"name"`
	Runtime      string            `yaml:"runtime"`
	Description  string            `yaml:"description"`
	SystemPrompt string            `yaml:"systemPrompt"`
	Model        *projectModel     `yaml:"model"`
	Tools        *projectTools     `yaml:"tools"`
	Skills       []projectSkill    `yaml:"skills"`
	Deployment   *projectDeploy    `yaml:"deployment"`
	Image        string            `yaml:"image"`
	Command      []string          `yaml:"command"`
	Args         []string          `yaml:"args"`
	Extras       map[string]string `yaml:"extras"`
}

type projectModel struct {
	Provider string            `yaml:"provider"`
	ModelID  string            `yaml:"modelId"`
	APIKey   string            `yaml:"apiKey"`
	Options  map[string]string `yaml:"options"`
}

type projectTools struct {
	Tools           []projectTool   `yaml:"tools"`
	InterruptConfig map[string]bool `yaml:"interruptConfig"`
}

type projectTool struct {
	Name          string `yaml:"name"`
	MCPServerName string `yaml:"mcpServerName"`
	MCPServerURL  string `yaml:"mcpServerUrl"`
}

type projectSkill struct {
	Type         string `yaml:"type"`
	Name         string `yaml:"name"`
	Description  string `yaml:"description"`
	Instructions string `yaml:"instructions"`
	Ref          string `yaml:"ref"`
}

type projectDeploy struct {
	Replicas  int32                   `yaml:"replicas"`
	Resources *projectDeployResources `yaml:"resources"`
}

type projectDeployResources struct {
	Requests map[string]string `yaml:"requests"`
	Limits   map[string]string `yaml:"limits"`
}

func agentDeployCmd() *cobra.Command {
	var (
		configFile string
		projectDir string
		dryRun     bool
		apiKey     string
	)

	cmd := &cobra.Command{
		Use:   "deploy [name]",
		Short: "Deploy an agent from a project directory",
		Long:  "Parses agentscope.yaml, embeds AGENTS.md, and pushes the agent configuration.",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			dir := projectDir
			cfgPath := filepath.Join(dir, configFile)

			data, err := os.ReadFile(cfgPath)
			if err != nil {
				return fmt.Errorf("reading config %s: %w", cfgPath, err)
			}

			var cfg projectConfig
			if err := yaml.Unmarshal(data, &cfg); err != nil {
				return fmt.Errorf("parsing %s: %w", cfgPath, err)
			}

			name := cfg.Name
			if len(args) > 0 {
				name = args[0]
			}
			if name == "" {
				return fmt.Errorf("agent name required (set 'name' in %s or pass as argument)", configFile)
			}

			// Embed AGENTS.md into system prompt if present and no explicit systemPrompt.
			if cfg.SystemPrompt == "" {
				agentsMD := filepath.Join(dir, "AGENTS.md")
				if md, err := os.ReadFile(agentsMD); err == nil && len(md) > 0 {
					cfg.SystemPrompt = string(md)
				}
			}

			// Override API key from flag or env.
			if apiKey != "" && cfg.Model != nil {
				cfg.Model.APIKey = apiKey
			}
			if cfg.Model != nil && cfg.Model.APIKey == "" {
				if envKey := os.Getenv("AGENTSCOPE_MODEL_API_KEY"); envKey != "" {
					cfg.Model.APIKey = envKey
				}
			}

			body := buildPushBody(&cfg)

			if dryRun {
				out, _ := json.MarshalIndent(body, "", "  ")
				fmt.Printf("Dry run — push body for agent %q:\n%s\n", name, string(out))
				return nil
			}

			encoded, err := json.Marshal(body)
			if err != nil {
				return fmt.Errorf("encoding push body: %w", err)
			}

			client := newAPIClient()
			resp, err := client.Post(
				fmt.Sprintf("%s/api/v1/agents/%s/push?namespace=%s", apiEndpoint, name, namespace),
				"application/json",
				bytes.NewReader(encoded),
			)
			if err != nil {
				return err
			}
			defer resp.Body.Close()

			if resp.StatusCode >= 400 {
				respBody, _ := io.ReadAll(resp.Body)
				return fmt.Errorf("deploy failed (%d): %s", resp.StatusCode, string(respBody))
			}

			var result struct {
				Revision         string `json:"revision"`
				CreatedResources []struct {
					Kind string `json:"kind"`
					Name string `json:"name"`
				} `json:"createdResources"`
			}
			json.NewDecoder(resp.Body).Decode(&result)

			fmt.Printf("Agent %q deployed (revision: %s)\n", name, result.Revision)
			for _, r := range result.CreatedResources {
				fmt.Printf("  %s: %s/%s\n", r.Kind, namespace, r.Name)
			}
			return nil
		},
	}

	cmd.Flags().StringVarP(&configFile, "config", "c", "agentscope.yaml", "Config file name")
	cmd.Flags().StringVarP(&projectDir, "dir", "d", ".", "Project directory")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "Print the push body without sending")
	cmd.Flags().StringVar(&apiKey, "api-key", "", "Model API key (overrides config and env)")
	return cmd
}

func buildPushBody(cfg *projectConfig) map[string]interface{} {
	body := map[string]interface{}{}

	if cfg.Runtime != "" {
		body["runtime"] = cfg.Runtime
	}
	if cfg.Description != "" {
		body["description"] = cfg.Description
	}
	if cfg.SystemPrompt != "" {
		body["systemPrompt"] = cfg.SystemPrompt
	}
	if cfg.Image != "" {
		body["image"] = cfg.Image
	}
	if len(cfg.Command) > 0 {
		body["command"] = cfg.Command
	}
	if len(cfg.Args) > 0 {
		body["args"] = cfg.Args
	}
	if cfg.Model != nil {
		model := map[string]interface{}{
			"provider": cfg.Model.Provider,
			"modelId":  cfg.Model.ModelID,
		}
		if cfg.Model.APIKey != "" {
			model["apiKey"] = cfg.Model.APIKey
		}
		if len(cfg.Model.Options) > 0 {
			model["options"] = cfg.Model.Options
		}
		body["model"] = model
	}
	if cfg.Tools != nil && len(cfg.Tools.Tools) > 0 {
		tools := make([]map[string]interface{}, 0, len(cfg.Tools.Tools))
		for _, t := range cfg.Tools.Tools {
			tool := map[string]interface{}{"name": t.Name}
			if t.MCPServerName != "" {
				tool["mcpServerName"] = t.MCPServerName
			}
			if t.MCPServerURL != "" {
				tool["mcpServerUrl"] = t.MCPServerURL
			}
			tools = append(tools, tool)
		}
		toolsSpec := map[string]interface{}{"tools": tools}
		if len(cfg.Tools.InterruptConfig) > 0 {
			toolsSpec["interruptConfig"] = cfg.Tools.InterruptConfig
		}
		body["tools"] = toolsSpec
	}
	if len(cfg.Skills) > 0 {
		skills := make([]map[string]interface{}, 0, len(cfg.Skills))
		for _, s := range cfg.Skills {
			skill := map[string]interface{}{"type": s.Type, "name": s.Name}
			if s.Description != "" {
				skill["description"] = s.Description
			}
			if s.Instructions != "" {
				skill["instructions"] = s.Instructions
			}
			if s.Ref != "" {
				skill["ref"] = s.Ref
			}
			skills = append(skills, skill)
		}
		body["skills"] = skills
	}
	if cfg.Deployment != nil {
		deploy := map[string]interface{}{}
		if cfg.Deployment.Replicas > 0 {
			deploy["replicas"] = cfg.Deployment.Replicas
		}
		if cfg.Deployment.Resources != nil {
			res := map[string]interface{}{}
			if len(cfg.Deployment.Resources.Requests) > 0 {
				res["requests"] = cfg.Deployment.Resources.Requests
			}
			if len(cfg.Deployment.Resources.Limits) > 0 {
				res["limits"] = cfg.Deployment.Resources.Limits
			}
			deploy["resources"] = res
		}
		body["deployment"] = deploy
	}
	if len(cfg.Extras) > 0 {
		body["extras"] = cfg.Extras
	}
	return body
}
