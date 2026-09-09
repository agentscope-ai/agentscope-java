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
	"io"
	"net/http"
	"net/url"

	"github.com/spf13/cobra"
)

func runtimeCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "runtime", Short: "Manage the local runtime daemon and control-plane runtime resources"}
	cmd.AddCommand(
		localRuntimeStartCmd(),
		localRuntimeStopCmd(),
		localRuntimeRestartCmd(),
		localRuntimeStatusCmd(),
		localRuntimeLogsCmd(),
		localRuntimeProbeCmd(),
		runtimeEnrollmentTokenCmd(),
		runtimeDiagnoseCmd(),
		runtimeHostCmd(),
		runtimeProfileCmd(),
		runtimePoolCmd(),
	)
	return cmd
}

func runtimeEnrollmentTokenCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "enrollment-token", Short: "Create scoped Runtime Host enrollment tokens"}
	cmd.AddCommand(&cobra.Command{
		Use:   "create",
		Short: "Create a short-lived enrollment token for `agentscope connect`",
		RunE: func(*cobra.Command, []string) error {
			body, err := json.Marshal(map[string]string{"tenant": tenant, "namespace": namespace})
			if err != nil {
				return err
			}
			return printResponse(doAPI(http.MethodPost, "/api/v1/runtime-host-enrollment-tokens", body))
		},
	})
	return cmd
}

func runtimeDiagnoseCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "diagnose ATTEMPT_ID",
		Short: "Collect an ExecutionAttempt, its Run graph, and durable provider events",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			attemptID := url.PathEscape(args[0])
			var attemptResponse struct {
				Attempt struct {
					ID                string          `json:"id"`
					AgentTaskID       string          `json:"agentTaskId"`
					RunID             string          `json:"runId"`
					NodeID            string          `json:"nodeId"`
					State             string          `json:"state"`
					BackendKind       string          `json:"backendKind"`
					RuntimeProfile    string          `json:"runtimeProfileName"`
					RuntimePool       string          `json:"runtimePoolName"`
					HostID            string          `json:"hostId"`
					ProviderSessionID string          `json:"providerSessionId"`
					WorkspaceKey      string          `json:"workspaceKey"`
					Result            json.RawMessage `json:"result"`
					FailureCode       string          `json:"failureCode"`
					FailureMessage    string          `json:"failureMessage"`
					CreatedAt         string          `json:"createdAt"`
					StartedAt         string          `json:"startedAt"`
					CompletedAt       string          `json:"completedAt"`
				} `json:"attempt"`
			}
			if err := runtimeGetJSON("/api/v1/execution-attempts/"+attemptID, &attemptResponse); err != nil {
				return err
			}
			if attemptResponse.Attempt.RunID == "" {
				return fmt.Errorf("execution attempt response has no runId")
			}
			var graph, events any
			runID := url.PathEscape(attemptResponse.Attempt.RunID)
			if err := runtimeGetJSON("/api/v1/orchestration-runs/"+runID+"/graph", &graph); err != nil {
				return err
			}
			if err := runtimeGetJSON("/api/v1/orchestration-runs/"+runID+"/events?limit=500", &events); err != nil {
				return err
			}
			recommendation := "Inspect the provider events and daemon logs with `agentscope runtime logs -f`."
			switch attemptResponse.Attempt.FailureCode {
			case "provider_permission_denied":
				recommendation = "The provider refused a required tool. Check the Agent hosted Settings allowlist and the resolved RuntimeProfile."
			case "provider_unavailable":
				recommendation = "Run `agentscope runtime probe` and reconnect so the provider is detected and registered."
			case "workspace_prepare_failed", "definition_materialize_failed":
				recommendation = "Check the workspace root permissions and daemon logs for this Attempt ID."
			}
			output := map[string]any{
				"attemptId": args[0], "attempt": attemptResponse.Attempt,
				"graph": graph, "events": events, "recommendation": recommendation,
			}
			encoded, _ := json.MarshalIndent(output, "", "  ")
			fmt.Println(string(encoded))
			return nil
		},
	}
}

func runtimeGetJSON(path string, target any) error {
	response, err := doAPI(http.MethodGet, path, nil)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 16<<20))
	if err != nil {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("request %s failed (%d): %s", path, response.StatusCode, body)
	}
	if err := json.Unmarshal(body, target); err != nil {
		return fmt.Errorf("decode %s: %w", path, err)
	}
	return nil
}
func runtimeHostCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "host"}
	cmd.AddCommand(&cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/runtime-hosts?namespace="+url.QueryEscape(namespace), nil))
	}}, &cobra.Command{Use: "get HOST_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/runtime-hosts/"+url.PathEscape(args[0]), nil))
	}})
	return cmd
}
func runtimeProfileCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "profile"}
	cmd.AddCommand(runtimeApplyCmd("runtime-profiles"), &cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/runtime-profiles?namespace="+url.QueryEscape(namespace), nil))
	}})
	return cmd
}
func runtimePoolCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "pool"}
	cmd.AddCommand(runtimeApplyCmd("runtime-pools"), &cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/runtime-pools?namespace="+url.QueryEscape(namespace), nil))
	}})
	return cmd
}
func runtimeApplyCmd(resource string) *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "apply", RunE: func(*cobra.Command, []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doAPI(http.MethodPost, "/api/v1/"+resource, body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Resource YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
