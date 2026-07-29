package main

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"
)

func initCmd() *cobra.Command {
	var dir string
	cmd := &cobra.Command{
		Use:   "init [name]",
		Short: "Initialize a new AgentScope project",
		Long:  "Generates an agent project skeleton with agentscope.yaml, AGENTS.md, and supporting directories.",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			root := filepath.Join(dir, name)

			if err := os.MkdirAll(root, 0o755); err != nil {
				return fmt.Errorf("creating project directory: %w", err)
			}
			for _, sub := range []string{"skills", "tools"} {
				if err := os.MkdirAll(filepath.Join(root, sub), 0o755); err != nil {
					return fmt.Errorf("creating %s directory: %w", sub, err)
				}
			}

			configContent := fmt.Sprintf(`name: %s
runtime: agentscope-java
description: ""

model:
  provider: dashscope
  modelId: qwen-max
  # apiKey: (set via AGENTSCOPE_MODEL_API_KEY or deploy --api-key)

# systemPrompt: |
#   You are a helpful assistant.

# tools:
#   - name: search
#     mcpServerName: search-server
#     mcpServerUrl: https://mcp.example.com/search

# skills:
#   - type: inline
#     name: greet
#     instructions: "Greet the user by name."

# deployment:
#   replicas: 1
#   resources:
#     requests:
#       cpu: "200m"
#       memory: "256Mi"
`, name)

			agentsMD := fmt.Sprintf("# %s\n\nDescribe your agent's purpose, capabilities, and behavior here.\n\nThis file is embedded in the agent configuration during `aistioctl agent deploy`.\n", name)

			files := map[string]string{
				"agentscope.yaml": configContent,
				"AGENTS.md":       agentsMD,
			}

			for filename, content := range files {
				path := filepath.Join(root, filename)
				if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
					return fmt.Errorf("writing %s: %w", filename, err)
				}
			}

			fmt.Printf("Project %q initialized at %s/\n", name, root)
			fmt.Println("  agentscope.yaml  — agent configuration")
			fmt.Println("  AGENTS.md        — agent description (embedded on deploy)")
			fmt.Println("  skills/          — inline skill definitions")
			fmt.Println("  tools/           — tool configuration")
			fmt.Println()
			fmt.Println("Next steps:")
			fmt.Printf("  cd %s && $EDITOR agentscope.yaml\n", root)
			fmt.Printf("  aistioctl agent deploy %s\n", name)
			return nil
		},
	}
	cmd.Flags().StringVarP(&dir, "dir", "d", ".", "Parent directory for the project")
	return cmd
}
