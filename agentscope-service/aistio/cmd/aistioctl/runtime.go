package main

import (
	"net/http"
	"net/url"

	"github.com/spf13/cobra"
)

func runtimeCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "runtime", Short: "Manage Runtime Hosts, profiles, and pools"}
	cmd.AddCommand(runtimeHostCmd(), runtimeProfileCmd(), runtimePoolCmd())
	return cmd
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
