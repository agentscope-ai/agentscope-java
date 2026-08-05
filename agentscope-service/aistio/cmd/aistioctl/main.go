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

	"github.com/spf13/cobra"
)

var (
	apiEndpoint string
	apiToken    string
	namespace   string
)

func main() {
	rootCmd := &cobra.Command{
		Use:   "aistioctl",
		Short: "CLI for Aistio",
		Long:  "aistioctl manages Aistio installations and agents.",
	}

	rootCmd.PersistentFlags().StringVar(&apiEndpoint, "api-endpoint", "http://localhost:8080", "Control plane REST API endpoint")
	rootCmd.PersistentFlags().StringVar(&apiToken, "api-token", os.Getenv("AGENTSCOPE_API_TOKEN"), "Bearer token for API authentication")
	rootCmd.PersistentFlags().StringVarP(&namespace, "namespace", "n", "default", "Kubernetes namespace")

	rootCmd.AddCommand(initCmd())
	rootCmd.AddCommand(installCmd())
	rootCmd.AddCommand(verifyCmd())
	rootCmd.AddCommand(agentCmd())
	rootCmd.AddCommand(sessionCmd())
	rootCmd.AddCommand(teamCmd())
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
			fmt.Println("aistioctl version 0.2.0")
		},
	}
}
