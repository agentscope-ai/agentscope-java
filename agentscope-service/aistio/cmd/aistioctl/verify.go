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

func verifyCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "verify-install",
		Short: "Verify Aistio installation",
		RunE: func(cmd *cobra.Command, args []string) error {
			fmt.Println("Verifying Aistio installation...")

			checks := []struct {
				name string
				fn   func() error
			}{
				{"CRDs installed", checkCRDs},
				{"Controller running", checkController},
				{"REST API reachable", checkAPI},
			}

			allPassed := true
			for _, check := range checks {
				err := check.fn()
				if err != nil {
					fmt.Printf("  x %s: %v\n", check.name, err)
					allPassed = false
				} else {
					fmt.Printf("  ok %s\n", check.name)
				}
			}

			if !allPassed {
				return fmt.Errorf("verification failed")
			}
			fmt.Println("All checks passed!")
			return nil
		},
	}
}

func checkCRDs() error {
	// TODO: Use discovery client to check CRDs
	return nil
}

func checkController() error {
	// TODO: Check deployment status
	return nil
}

func checkAPI() error {
	client := newAPIClient()
	_, err := client.Get(fmt.Sprintf("%s/api/v1/version", apiEndpoint))
	return err
}
