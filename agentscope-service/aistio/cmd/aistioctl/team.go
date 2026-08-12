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
	"bytes"
	"encoding/json"
	"fmt"
	"io"

	"github.com/spf13/cobra"
)

func teamCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "team",
		Short: "Manage agent teams",
	}
	cmd.AddCommand(teamListCmd())
	cmd.AddCommand(teamGetCmd())
	cmd.AddCommand(teamTasksCmd())
	cmd.AddCommand(teamMembersCmd())
	cmd.AddCommand(teamMessagesCmd())
	return cmd
}

func teamListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List all teams",
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			resp, err := client.Get(fmt.Sprintf("%s/api/v1/teams?namespace=%s", apiEndpoint, namespace))
			if err != nil {
				return err
			}
			defer resp.Body.Close()
			body, _ := io.ReadAll(resp.Body)

			var result struct {
				Items []struct {
					Name      string `json:"name"`
					Namespace string `json:"namespace"`
					Phase     string `json:"phase"`
					Members   int    `json:"memberCount"`
					Tasks     struct {
						Total     int32 `json:"total"`
						Completed int32 `json:"completed"`
					} `json:"tasks"`
				} `json:"items"`
			}
			json.Unmarshal(body, &result)

			fmt.Printf("%-20s %-12s %-10s %-10s %-15s\n", "NAME", "NAMESPACE", "PHASE", "MEMBERS", "TASKS")
			for _, t := range result.Items {
				fmt.Printf("%-20s %-12s %-10s %-10d %d/%d\n",
					t.Name, t.Namespace, t.Phase, t.Members, t.Tasks.Completed, t.Tasks.Total)
			}
			return nil
		},
	}
}

func teamGetCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "get [name]",
		Short: "Get team details",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			resp, err := client.Get(fmt.Sprintf("%s/api/v1/teams/%s?namespace=%s", apiEndpoint, args[0], namespace))
			if err != nil {
				return err
			}
			defer resp.Body.Close()
			body, _ := io.ReadAll(resp.Body)
			var out bytes.Buffer
			json.Indent(&out, body, "", "  ")
			fmt.Println(out.String())
			return nil
		},
	}
}

func teamTasksCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "tasks [team-name]",
		Short: "List tasks for a team",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			resp, err := client.Get(fmt.Sprintf("%s/api/v1/teams/%s/tasks?namespace=%s", apiEndpoint, args[0], namespace))
			if err != nil {
				return err
			}
			defer resp.Body.Close()
			body, _ := io.ReadAll(resp.Body)

			var result struct {
				Items []struct {
					TaskID  string `json:"taskId"`
					Subject string `json:"subject"`
					State   string `json:"state"`
					Owner   string `json:"owner"`
				} `json:"items"`
			}
			json.Unmarshal(body, &result)

			fmt.Printf("%-15s %-30s %-15s %-15s\n", "TASK ID", "SUBJECT", "STATE", "OWNER")
			for _, t := range result.Items {
				fmt.Printf("%-15s %-30s %-15s %-15s\n", t.TaskID, t.Subject, t.State, t.Owner)
			}
			return nil
		},
	}
}

func teamMessagesCmd() *cobra.Command {
	var limit int
	cmd := &cobra.Command{
		Use:   "messages [team-name]",
		Short: "List recent messages for a team",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			resp, err := client.Get(fmt.Sprintf("%s/api/v1/teams/%s/messages?namespace=%s&limit=%d",
				apiEndpoint, args[0], namespace, limit))
			if err != nil {
				return err
			}
			defer resp.Body.Close()
			body, _ := io.ReadAll(resp.Body)

			var result struct {
				Messages []struct {
					FromMember string `json:"fromMember"`
					ToMember   string `json:"toMember"`
					Content    string `json:"content"`
					Kind       string `json:"kind"`
					Delivered  bool   `json:"delivered"`
					CreatedAt  string `json:"createdAt"`
				} `json:"messages"`
			}
			json.Unmarshal(body, &result)

			fmt.Printf("%-26s %-15s %-15s %-10s %s\n", "CREATED", "FROM", "TO", "DELIVERED", "CONTENT")
			for _, m := range result.Messages {
				fmt.Printf("%-26s %-15s %-15s %-10t %s\n", m.CreatedAt, m.FromMember, m.ToMember, m.Delivered, m.Content)
			}
			return nil
		},
	}
	cmd.Flags().IntVar(&limit, "limit", 50, "Maximum number of messages to return")
	return cmd
}

func teamMembersCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "members [team-name]",
		Short: "List members of a team",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			resp, err := client.Get(fmt.Sprintf("%s/api/v1/teams/%s/members?namespace=%s", apiEndpoint, args[0], namespace))
			if err != nil {
				return err
			}
			defer resp.Body.Close()
			body, _ := io.ReadAll(resp.Body)

			var result struct {
				Members []struct {
					Name     string `json:"name"`
					AgentRef string `json:"agentRef"`
					Phase    string `json:"phase"`
					Origin   string `json:"origin"`
					Session  string `json:"sessionId"`
				} `json:"members"`
			}
			json.Unmarshal(body, &result)

			fmt.Printf("%-20s %-20s %-12s %-10s %-25s\n", "NAME", "AGENT", "PHASE", "ORIGIN", "SESSION")
			for _, m := range result.Members {
				fmt.Printf("%-20s %-20s %-12s %-10s %-25s\n", m.Name, m.AgentRef, m.Phase, m.Origin, m.Session)
			}
			return nil
		},
	}
}
