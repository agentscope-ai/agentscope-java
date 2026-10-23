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
	"encoding/json"
	"fmt"

	"github.com/spf13/cobra"
)

func proxyStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "proxy-status",
		Short: "Show data plane connection status for all agents",
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			resp, err := client.Get(fmt.Sprintf("%s/api/v1/agents?namespace=%s", apiEndpoint, namespace))
			if err != nil {
				return err
			}
			defer resp.Body.Close()

			var result struct {
				Items []json.RawMessage `json:"items"`
			}
			json.NewDecoder(resp.Body).Decode(&result)

			fmt.Printf("%-20s %-15s %-12s %-12s %-25s\n",
				"AGENT", "CONTRACT LEVEL", "STATUS", "RUNTIME", "LAST PROBE")

			for _, raw := range result.Items {
				var agent struct {
					Name   string `json:"name"`
					Status struct {
						DataPlaneInfo *struct {
							ContractLevel int32  `json:"contractLevel"`
							LastProbeAt   string `json:"lastProbeAt"`
						} `json:"dataPlaneInfo"`
						Conditions []struct {
							Type   string `json:"type"`
							Status string `json:"status"`
						} `json:"conditions"`
					} `json:"status"`
					Spec struct {
						Runtime string `json:"runtime"`
					} `json:"spec"`
				}
				json.Unmarshal(raw, &agent)

				cl := int32(0)
				lastProbe := "never"
				status := "disconnected"
				if agent.Status.DataPlaneInfo != nil {
					cl = agent.Status.DataPlaneInfo.ContractLevel
					lastProbe = agent.Status.DataPlaneInfo.LastProbeAt
				}
				for _, c := range agent.Status.Conditions {
					if c.Type == "DataPlaneConnected" && c.Status == "True" {
						status = "connected"
					}
				}

				fmt.Printf("%-20s %-15d %-12s %-12s %-25s\n",
					agent.Name, cl, status, agent.Spec.Runtime, lastProbe)
			}
			return nil
		},
	}
}
