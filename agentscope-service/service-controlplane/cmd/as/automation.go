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

func automationCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "automation", Short: "Manage Cron, Webhook, and Channel automations"}
	cmd.AddCommand(filePostCommand("create", "Automation YAML or JSON", "/api/v1/automations"),
		&cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
			return printResponse(doAPI(http.MethodGet, "/api/v1/automations?namespace="+url.QueryEscape(namespace), nil))
		}},
		&cobra.Command{Use: "get AUTOMATION_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
			return printResponse(doAPI(http.MethodGet, "/api/v1/automations/"+url.PathEscape(args[0]), nil))
		}}, automationTriggerCmd())
	return cmd
}

func automationTriggerCmd() *cobra.Command {
	var key, file string
	cmd := &cobra.Command{Use: "trigger AUTOMATION_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		body := []byte(`{}`)
		var err error
		if file != "" {
			body, err = readYAMLAsJSON(file)
			if err != nil {
				return err
			}
		}
		req, err := http.NewRequest(http.MethodPost, apiEndpoint+"/api/v1/automations/"+url.PathEscape(args[0])+"/trigger", bytesReader(body))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Idempotency-Key", key)
		return printResponse(newAPIClient().Do(req))
	}}
	cmd.Flags().StringVar(&key, "idempotency-key", "", "Unique trigger delivery key")
	cmd.Flags().StringVarP(&file, "file", "f", "", "Trigger payload YAML or JSON")
	_ = cmd.MarkFlagRequired("idempotency-key")
	return cmd
}
