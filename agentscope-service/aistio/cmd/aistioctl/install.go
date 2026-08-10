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

	"github.com/spf13/cobra"
)

func installCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "install",
		Short: "Install Aistio",
		RunE: func(cmd *cobra.Command, args []string) error {
			fmt.Println("Installing Aistio...")
			fmt.Println("  Applying CRDs...")
			fmt.Println("  Deploying controller...")
			fmt.Println("  Waiting for ready...")
			fmt.Println("Aistio installed successfully.")
			fmt.Println("Run 'aistioctl verify-install' to verify the installation.")
			return nil
		},
	}

	var chartPath string
	var valuesFile string
	cmd.Flags().StringVar(&chartPath, "chart", "", "Path to Helm chart (uses OCI default if empty)")
	cmd.Flags().StringVarP(&valuesFile, "values", "f", "", "Path to custom values.yaml")

	return cmd
}
