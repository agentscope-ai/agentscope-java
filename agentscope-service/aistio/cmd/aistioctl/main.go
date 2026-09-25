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

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"
	"github.com/spring-ai-alibaba/aistio/internal/version"
)

var (
	apiEndpoint string
	apiToken    string
	tenant      string
	namespace   string
)

func defaultAPIEndpoint() string {
	for _, key := range []string{"AGENTSCOPE_CONTROL_PLANE", "AISTIO_CONTROL_PLANE"} {
		if value := os.Getenv(key); value != "" {
			return strings.TrimRight(value, "/")
		}
	}
	return "http://localhost:8080"
}

func main() {
	commandName := filepath.Base(os.Args[0])
	if commandName != "agentscope" {
		commandName = "aistioctl"
	}
	rootCmd := &cobra.Command{
		Use:   commandName,
		Short: "CLI for AgentScope",
		Long:  commandName + " manages AgentScope agents, collaboration resources, and local runtimes.",
	}

	rootCmd.PersistentFlags().StringVar(&apiEndpoint, "api-endpoint", defaultAPIEndpoint(), "Control plane REST API endpoint")
	rootCmd.PersistentFlags().StringVar(&apiToken, "api-token", os.Getenv("AGENTSCOPE_API_TOKEN"), "Bearer token for API authentication")
	rootCmd.PersistentFlags().StringVar(&tenant, "tenant", "default", "Collaboration tenant")
	rootCmd.PersistentFlags().StringVarP(&namespace, "namespace", "n", "default", "Kubernetes namespace")

	rootCmd.AddCommand(initCmd())
	rootCmd.AddCommand(connectCmd())
	rootCmd.AddCommand(installCmd())
	rootCmd.AddCommand(verifyCmd())
	rootCmd.AddCommand(agentCmd())
	rootCmd.AddCommand(sessionCmd())
	rootCmd.AddCommand(teamCmd())
	rootCmd.AddCommand(issueCmd())
	rootCmd.AddCommand(taskCmd())
	rootCmd.AddCommand(artifactCmd())
	rootCmd.AddCommand(automationCmd())
	rootCmd.AddCommand(runtimeCmd())
	rootCmd.AddCommand(orchestrationCmd())
	rootCmd.AddCommand(approvalCmd())
	rootCmd.AddCommand(inboxCmd())
	rootCmd.AddCommand(proxyStatusCmd())
	rootCmd.AddCommand(versionCmd())

	if err := rootCmd.Execute(); err != nil {
		os.Exit(1)
	}
}

func versionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print version information",
		Run: func(cmd *cobra.Command, args []string) {
			fmt.Printf("%s version %s\n", cmd.Root().Name(), version.Version)
		},
	}
}
