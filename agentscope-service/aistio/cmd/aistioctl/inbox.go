package main

import (
	"net/http"
	"net/url"

	"github.com/spf13/cobra"
)

func inboxCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "inbox", Short: "Inspect human attention items"}
	cmd.AddCommand(inboxListCmd(), inboxActionCmd("read"), inboxActionCmd("archive"))
	return cmd
}

func inboxListCmd() *cobra.Command {
	var archived bool
	cmd := &cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		q := url.Values{"tenant": []string{tenant}, "namespace": []string{namespace}}
		if archived {
			q.Set("archived", "true")
		}
		return printResponse(doAPI(http.MethodGet, "/api/v1/inbox?"+q.Encode(), nil))
	}}
	cmd.Flags().BoolVar(&archived, "archived", false, "List archived items")
	return cmd
}

func inboxActionCmd(action string) *cobra.Command {
	return &cobra.Command{Use: action + " INBOX_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodPost, "/api/v1/inbox/"+url.PathEscape(args[0])+"/"+action, nil))
	}}
}
