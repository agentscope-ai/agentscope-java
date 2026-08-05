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

func sessionCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:     "session",
		Aliases: []string{"sessions"},
		Short:   "Manage agent sessions",
	}
	cmd.AddCommand(sessionListCmd())
	cmd.AddCommand(sessionGetCmd())
	cmd.AddCommand(sessionCompressCmd())
	cmd.AddCommand(sessionTerminateCmd())
	return cmd
}

func sessionListCmd() *cobra.Command {
	var agent, phase, framework, team string
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List sessions",
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			url := fmt.Sprintf("%s/api/v1/sessions?namespace=%s", apiEndpoint, namespace)
			if agent != "" {
				url += "&agent=" + agent
			}
			if phase != "" {
				url += "&phase=" + phase
			}
			if framework != "" {
				url += "&framework=" + framework
			}
			if team != "" {
				url += "&team=" + team
			}
			resp, err := client.Get(url)
			if err != nil {
				return err
			}
			defer resp.Body.Close()

			if resp.StatusCode >= 400 {
				body, _ := io.ReadAll(resp.Body)
				return fmt.Errorf("request failed (%d): %s", resp.StatusCode, string(body))
			}

			var result struct {
				Sessions []struct {
					SessionID string `json:"sessionId"`
					AgentName string `json:"agentName"`
					Namespace string `json:"namespace"`
					Framework string `json:"framework"`
					Phase     string `json:"phase"`
					TeamID    string `json:"teamId"`
					TeamRole  string `json:"teamRole"`
				} `json:"sessions"`
			}
			if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
				return err
			}

			fmt.Printf("%-38s %-20s %-12s %-12s %-14s %-14s\n",
				"SESSION ID", "AGENT", "NAMESPACE", "PHASE", "FRAMEWORK", "TEAM")
			for _, sess := range result.Sessions {
				fmt.Printf("%-38s %-20s %-12s %-12s %-14s %-14s\n",
					sess.SessionID, sess.AgentName, sess.Namespace, sess.Phase, sess.Framework, sess.TeamID)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&agent, "agent", "", "Filter by agent name")
	cmd.Flags().StringVar(&phase, "phase", "", "Filter by session phase")
	cmd.Flags().StringVar(&framework, "framework", "", "Filter by agent framework")
	cmd.Flags().StringVar(&team, "team", "", "Filter by team ID")
	return cmd
}

func sessionGetCmd() *cobra.Command {
	var agent string
	cmd := &cobra.Command{
		Use:   "get [session-id]",
		Short: "Get session details",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			url := fmt.Sprintf("%s/api/v1/sessions/%s?namespace=%s", apiEndpoint, args[0], namespace)
			if agent != "" {
				url += "&agent=" + agent
			}
			resp, err := client.Get(url)
			if err != nil {
				return err
			}
			defer resp.Body.Close()

			body, _ := io.ReadAll(resp.Body)
			if resp.StatusCode >= 400 {
				return fmt.Errorf("request failed (%d): %s", resp.StatusCode, string(body))
			}
			var out bytes.Buffer
			json.Indent(&out, body, "", "  ")
			fmt.Println(out.String())
			return nil
		},
	}
	cmd.Flags().StringVar(&agent, "agent", "", "Agent name (required when session-id is not a UUID)")
	return cmd
}

func sessionCompressCmd() *cobra.Command {
	var agent string
	cmd := &cobra.Command{
		Use:   "compress [session-id]",
		Short: "Trigger context compression for a session",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return sessionCommand(args[0], agent, "compress")
		},
	}
	cmd.Flags().StringVar(&agent, "agent", "", "Agent name (required when session-id is not a UUID)")
	return cmd
}

func sessionTerminateCmd() *cobra.Command {
	var agent string
	cmd := &cobra.Command{
		Use:   "terminate [session-id]",
		Short: "Terminate a session",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return sessionCommand(args[0], agent, "terminate")
		},
	}
	cmd.Flags().StringVar(&agent, "agent", "", "Agent name (required when session-id is not a UUID)")
	return cmd
}

func sessionCommand(sessionID, agent, action string) error {
	client := newAPIClient()
	url := fmt.Sprintf("%s/api/v1/sessions/%s/%s?namespace=%s", apiEndpoint, sessionID, action, namespace)
	if agent != "" {
		url += "&agent=" + agent
	}
	resp, err := client.Post(url, "application/json", nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return fmt.Errorf("%s failed (%d): %s", action, resp.StatusCode, string(body))
	}
	fmt.Printf("Session %q: %s initiated\n", sessionID, action)
	return nil
}
