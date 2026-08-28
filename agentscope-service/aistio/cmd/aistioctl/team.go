package main

import (
	"net/http"
	"net/url"

	"github.com/spf13/cobra"
)

func teamCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "team", Short: "Manage persistent Agent teams"}
	cmd.AddCommand(teamApplyCmd(), teamListCmd(), teamGetCmd(), teamMembersCmd())
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
