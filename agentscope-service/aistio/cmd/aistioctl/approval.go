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
	"net/http"
	"net/url"

	"github.com/spf13/cobra"
)

func approvalCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "approval", Short: "Request, inspect, and decide approvals"}
	cmd.AddCommand(approvalRequestCmd(), approvalListCmd(), approvalGetCmd(), approvalDecisionCmd("approve", "approved"), approvalDecisionCmd("reject", "rejected"))
	return cmd
}
func approvalRequestCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "request", RunE: func(*cobra.Command, []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doAgentOrHumanAPI(http.MethodPost, "/api/v1/approvals", body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Approval YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
func approvalListCmd() *cobra.Command {
	var status string
	cmd := &cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		q := url.Values{"tenant": []string{tenant}, "namespace": []string{namespace}}
		if status != "" {
			q.Set("status", status)
		}
		return printResponse(doAPI(http.MethodGet, "/api/v1/approvals?"+q.Encode(), nil))
	}}
	cmd.Flags().StringVar(&status, "status", "", "pending, approved, rejected, or cancelled")
	return cmd
}
func approvalGetCmd() *cobra.Command {
	return &cobra.Command{Use: "get APPROVAL_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/approvals/"+url.PathEscape(args[0]), nil))
	}}
}
func approvalDecisionCmd(use, status string) *cobra.Command {
	var expected int64
	var file string
	cmd := &cobra.Command{Use: use + " APPROVAL_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		decision := json.RawMessage(`{}`)
		if file != "" {
			body, err := readYAMLAsJSON(file)
			if err != nil {
				return err
			}
			decision = body
		}
		return printResponse(doAPI(http.MethodPost, "/api/v1/approvals/"+url.PathEscape(args[0])+"/decide", jsonBody(map[string]any{"status": status, "expectedVersion": expected, "decision": decision})))
	}}
	cmd.Flags().Int64Var(&expected, "expected-version", 0, "Optimistic-lock version")
	cmd.Flags().StringVarP(&file, "file", "f", "", "Decision YAML or JSON")
	return cmd
}
