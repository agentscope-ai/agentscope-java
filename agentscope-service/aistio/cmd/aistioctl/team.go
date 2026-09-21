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
	"net/http"
	"net/url"

	"github.com/spf13/cobra"
)

func teamCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "team", Short: "Manage persistent Agent teams"}
	cmd.AddCommand(teamApplyCmd(), teamListCmd(), teamGetCmd(), teamMembersCmd(), teamCurrentCmd())
	return cmd
}
func teamApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "apply", Short: "Create a Team from YAML or JSON", RunE: func(*cobra.Command, []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doAPI(http.MethodPost, "/api/v1/teams", body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Team YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
func teamListCmd() *cobra.Command {
	return &cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		q := url.Values{"tenant": []string{tenant}, "namespace": []string{namespace}}
		return printResponse(doAPI(http.MethodGet, "/api/v1/teams?"+q.Encode(), nil))
	}}
}
func teamGetCmd() *cobra.Command {
	return &cobra.Command{Use: "get TEAM_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/teams/"+url.PathEscape(args[0]), nil))
	}}
}
func teamMembersCmd() *cobra.Command {
	return &cobra.Command{Use: "members TEAM_ID", Short: "List the Team roster", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/teams/"+url.PathEscape(args[0]), nil))
	}}
}
