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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package main

import (
	"net/http"
	"net/url"

	"github.com/spf13/cobra"
)

func orchestrationCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "orchestration", Short: "Manage definitions, runs, attempts, and runtime policies"}
	cmd.AddCommand(orchestrationDefinitionCmd(), orchestrationRunCmd(), executionAttemptCmd(), runtimePolicyCmd())
	return cmd
}

func orchestrationDefinitionCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "definition"}
	cmd.AddCommand(filePostCommand("create", "Definition YAML or JSON", "/api/v1/orchestration-definitions"),
		&cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
			return printResponse(doAPI(http.MethodGet, scopeQuery("/api/v1/orchestration-definitions"), nil))
		}},
		&cobra.Command{Use: "get DEFINITION_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
			return printResponse(doAPI(http.MethodGet, "/api/v1/orchestration-definitions/"+url.PathEscape(args[0]), nil))
		}},
		definitionFileAction("validate", "validate"), definitionFileAction("update", ""),
		&cobra.Command{Use: "publish DEFINITION_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
			return printResponse(doAPI(http.MethodPost, "/api/v1/orchestration-definitions/"+url.PathEscape(args[0])+"/publish", []byte(`{}`)))
		}}, definitionStartCmd())
	return cmd
}

func definitionFileAction(use, suffix string) *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: use + " DEFINITION_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		path := "/api/v1/orchestration-definitions/" + url.PathEscape(args[0])
		method := http.MethodPatch
		if suffix != "" {
			path += "/" + suffix
			method = http.MethodPost
		}
		return printResponse(doAPI(method, path, body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Request YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func definitionStartCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "start DEFINITION_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doAPI(http.MethodPost, "/api/v1/orchestration-definitions/"+url.PathEscape(args[0])+"/runs", body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Start request YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func orchestrationRunCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "run"}
	cmd.AddCommand(&cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		return printResponse(doAPI(http.MethodGet, scopeQuery("/api/v1/orchestration-runs"), nil))
	}}, runGetCmd("get", ""), runGetCmd("graph", "/graph"), runGetCmd("events", "/events"),
		runMutationCmd("pause"), runMutationCmd("resume"), runMutationCmd("cancel"), runRerunCmd(), runSignalCmd())
	return cmd
}

func runRerunCmd() *cobra.Command {
	var file, key string
	cmd := &cobra.Command{Use: "rerun RUN_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		var input any
		if file != "" {
			raw, err := readYAMLAsJSON(file)
			if err != nil {
				return err
			}
			input = jsonRaw(raw)
		}
		return printResponse(doAPI(http.MethodPost, "/api/v1/orchestration-runs/"+url.PathEscape(args[0])+"/rerun",
			jsonBody(map[string]any{"idempotencyKey": key, "input": input})))
	}}
	cmd.Flags().StringVar(&key, "idempotency-key", "", "Unique rerun key")
	cmd.Flags().StringVarP(&file, "file", "f", "", "Optional replacement input YAML or JSON")
	_ = cmd.MarkFlagRequired("idempotency-key")
	return cmd
}

func runGetCmd(use, suffix string) *cobra.Command {
	return &cobra.Command{Use: use + " RUN_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/orchestration-runs/"+url.PathEscape(args[0])+suffix, nil))
	}}
}

func runMutationCmd(action string) *cobra.Command {
	return &cobra.Command{Use: action + " RUN_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodPost, "/api/v1/orchestration-runs/"+url.PathEscape(args[0])+"/"+action, []byte(`{}`)))
	}}
}

func runSignalCmd() *cobra.Command {
	var file, key string
	cmd := &cobra.Command{Use: "signal RUN_ID NAME", Args: cobra.ExactArgs(2), RunE: func(_ *cobra.Command, args []string) error {
		payload := []byte(`null`)
		var err error
		if file != "" {
			payload, err = readYAMLAsJSON(file)
			if err != nil {
				return err
			}
		}
		body := jsonBody(map[string]any{"idempotencyKey": key, "payload": jsonRaw(payload)})
		return printResponse(doAPI(http.MethodPost, "/api/v1/orchestration-runs/"+url.PathEscape(args[0])+"/signals/"+url.PathEscape(args[1]), body))
	}}
	cmd.Flags().StringVar(&key, "idempotency-key", "", "Unique signal delivery key")
	cmd.Flags().StringVarP(&file, "file", "f", "", "Signal payload YAML or JSON")
	_ = cmd.MarkFlagRequired("idempotency-key")
	return cmd
}

type jsonRaw []byte

func (r jsonRaw) MarshalJSON() ([]byte, error) { return r, nil }

func executionAttemptCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "attempt"}
	cmd.AddCommand(&cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		return printResponse(doAPI(http.MethodGet, scopeQuery("/api/v1/execution-attempts"), nil))
	}}, &cobra.Command{Use: "get ATTEMPT_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/execution-attempts/"+url.PathEscape(args[0]), nil))
	}})
	return cmd
}

func runtimePolicyCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "runtime-policy"}
	cmd.AddCommand(&cobra.Command{Use: "get AGENT_REF", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/agent-runtime-policies/"+url.PathEscape(args[0])+"?tenant="+url.QueryEscape(tenant)+"&namespace="+url.QueryEscape(namespace), nil))
	}}, runtimePolicyPutCmd())
	return cmd
}

func runtimePolicyPutCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "put AGENT_REF", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doAPI(http.MethodPut, "/api/v1/agent-runtime-policies/"+url.PathEscape(args[0]), body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Runtime policy YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func scopeQuery(path string) string {
	return path + "?tenant=" + url.QueryEscape(tenant) + "&namespace=" + url.QueryEscape(namespace)
}
