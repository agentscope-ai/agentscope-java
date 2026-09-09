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
