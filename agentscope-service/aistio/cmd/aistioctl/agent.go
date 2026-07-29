package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/spf13/cobra"
)

func agentCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "agent",
		Short: "Manage agents",
	}

	cmd.AddCommand(agentListCmd())
	cmd.AddCommand(agentStatusCmd())
	cmd.AddCommand(agentPushCmd())
	cmd.AddCommand(agentDeployCmd())
	cmd.AddCommand(agentRevisionsCmd())
	cmd.AddCommand(agentRollbackCmd())
	cmd.AddCommand(agentAdoptCmd())

	return cmd
}

func agentListCmd() *cobra.Command {
	var typeFilter string
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List all agents",
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			url := fmt.Sprintf("%s/api/v1/agents?namespace=%s", apiEndpoint, namespace)
			if typeFilter != "" {
				url += "&type=" + typeFilter
			}
			resp, err := client.Get(url)
			if err != nil {
				return err
			}
			defer resp.Body.Close()

			var result struct {
				Items []struct {
					Name           string `json:"name"`
					Namespace      string `json:"namespace"`
					Type           string `json:"type"`
					Runtime        string `json:"runtime"`
					Replicas       string `json:"replicas"`
					ActiveSessions int32  `json:"activeSessions"`
					Revision       string `json:"revision"`
				} `json:"items"`
			}
			if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
				return err
			}

			fmt.Printf("%-20s %-12s %-12s %-18s %-10s %-10s %-10s\n",
				"NAMESPACE", "NAME", "TYPE", "RUNTIME", "REPLICAS", "SESSIONS", "REVISION")
			for _, a := range result.Items {
				fmt.Printf("%-20s %-12s %-12s %-18s %-10s %-10d %-10s\n",
					a.Namespace, a.Name, a.Type, a.Runtime, a.Replicas, a.ActiveSessions, a.Revision)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&typeFilter, "type", "", "Filter by agent type (Declarative|BYO)")
	return cmd
}

func agentStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "status [name]",
		Short: "Show agent status",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			resp, err := client.Get(fmt.Sprintf("%s/api/v1/agents/%s?namespace=%s", apiEndpoint, args[0], namespace))
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

func agentPushCmd() *cobra.Command {
	var configFile string
	cmd := &cobra.Command{
		Use:   "push [name]",
		Short: "Push agent configuration",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			data, err := os.ReadFile(configFile)
			if err != nil {
				return fmt.Errorf("reading config: %w", err)
			}

			client := newAPIClient()
			resp, err := client.Post(
				fmt.Sprintf("%s/api/v1/agents/%s/push?namespace=%s", apiEndpoint, args[0], namespace),
				"application/json",
				bytes.NewReader(data),
			)
			if err != nil {
				return err
			}
			defer resp.Body.Close()

			if resp.StatusCode >= 400 {
				body, _ := io.ReadAll(resp.Body)
				return fmt.Errorf("push failed (%d): %s", resp.StatusCode, string(body))
			}

			var result struct {
				Revision         string `json:"revision"`
				CreatedResources []struct {
					Kind string `json:"kind"`
					Name string `json:"name"`
				} `json:"createdResources"`
			}
			json.NewDecoder(resp.Body).Decode(&result)

			fmt.Printf("Agent %q pushed (revision: %s)\n", args[0], result.Revision)
			for _, r := range result.CreatedResources {
				fmt.Printf("  %s: %s/%s\n", r.Kind, namespace, r.Name)
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&configFile, "config", "c", "agentscope.yaml", "Agent configuration file")
	return cmd
}

func agentRevisionsCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "revisions [name] [revision]",
		Short: "List revision history, or show a specific revision's config snapshot",
		Long: "Without a revision, lists the agent's revision history.\n" +
			"With a revision argument, prints that revision's stored configuration snapshot.",
		Args: cobra.RangeArgs(1, 2),
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) == 2 {
				return showRevision(args[0], args[1])
			}
			return listRevisions(args[0])
		},
	}
}

func listRevisions(name string) error {
	client := newAPIClient()
	resp, err := client.Get(fmt.Sprintf("%s/api/v1/agents/%s/revisions?namespace=%s", apiEndpoint, name, namespace))
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("request failed (%d): %s", resp.StatusCode, string(body))
	}

	var result struct {
		Revisions []struct {
			Revision  string `json:"revision"`
			CreatedAt string `json:"createdAt"`
			Message   string `json:"message"`
		} `json:"revisions"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return err
	}

	if len(result.Revisions) == 0 {
		fmt.Println("No revisions found.")
		return nil
	}

	fmt.Printf("%-12s %-26s %s\n", "REVISION", "CREATED", "MESSAGE")
	for _, r := range result.Revisions {
		fmt.Printf("%-12s %-26s %s\n", r.Revision, r.CreatedAt, r.Message)
	}
	return nil
}

func showRevision(name, rev string) error {
	client := newAPIClient()
	resp, err := client.Get(fmt.Sprintf("%s/api/v1/agents/%s/revisions/%s?namespace=%s", apiEndpoint, name, rev, namespace))
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("request failed (%d): %s", resp.StatusCode, string(body))
	}

	var entry struct {
		Revision     string          `json:"revision"`
		CreatedAt    string          `json:"createdAt"`
		Message      string          `json:"message"`
		SpecSnapshot json.RawMessage `json:"specSnapshot"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&entry); err != nil {
		return err
	}

	fmt.Printf("Revision: %s\n", entry.Revision)
	fmt.Printf("Created:  %s\n", entry.CreatedAt)
	if entry.Message != "" {
		fmt.Printf("Message:  %s\n", entry.Message)
	}
	if len(entry.SpecSnapshot) == 0 {
		fmt.Println("\n(no config snapshot stored for this revision)")
		return nil
	}
	var out bytes.Buffer
	if err := json.Indent(&out, entry.SpecSnapshot, "", "  "); err != nil {
		return err
	}
	fmt.Printf("\nConfig snapshot:\n%s\n", out.String())
	return nil
}

func agentRollbackCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "rollback [name] [revision]",
		Short: "Rollback agent to a previous revision",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			revision := args[1]

			body := fmt.Sprintf(`{"revision":%q}`, revision)
			client := newAPIClient()
			resp, err := client.Post(
				fmt.Sprintf("%s/api/v1/agents/%s/rollback?namespace=%s", apiEndpoint, name, namespace),
				"application/json",
				strings.NewReader(body),
			)
			if err != nil {
				return err
			}
			defer resp.Body.Close()

			if resp.StatusCode >= 400 {
				respBody, _ := io.ReadAll(resp.Body)
				return fmt.Errorf("rollback failed (%d): %s", resp.StatusCode, string(respBody))
			}

			var result struct {
				Revision string `json:"revision"`
			}
			json.NewDecoder(resp.Body).Decode(&result)

			fmt.Printf("Agent %q rolled back to %s (new revision: %s)\n", name, revision, result.Revision)
			return nil
		},
	}
}

func agentAdoptCmd() *cobra.Command {
	var deployment string
	cmd := &cobra.Command{
		Use:   "adopt",
		Short: "Adopt an existing deployment as a managed agent",
		RunE: func(cmd *cobra.Command, args []string) error {
			client := newAPIClient()
			body := fmt.Sprintf(`{"deployment":%q,"namespace":%q}`, deployment, namespace)
			resp, err := client.Post(
				fmt.Sprintf("%s/api/v1/agents/%s/adopt?namespace=%s", apiEndpoint, deployment, namespace),
				"application/json",
				strings.NewReader(body),
			)
			if err != nil {
				return err
			}
			defer resp.Body.Close()

			if resp.StatusCode >= 400 {
				respBody, _ := io.ReadAll(resp.Body)
				return fmt.Errorf("adopt failed (%d): %s", resp.StatusCode, string(respBody))
			}

			fmt.Printf("Deployment %q adopted as managed agent\n", deployment)
			return nil
		},
	}
	cmd.Flags().StringVarP(&deployment, "deployment", "d", "", "Name of the deployment to adopt")
	_ = cmd.MarkFlagRequired("deployment")
	return cmd
}
