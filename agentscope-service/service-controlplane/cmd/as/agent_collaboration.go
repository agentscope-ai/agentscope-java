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
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"

	"github.com/spf13/cobra"
)

func contextValue(explicit string, keys ...string) (string, error) {
	if value := strings.TrimSpace(explicit); value != "" {
		return value, nil
	}
	for _, key := range keys {
		if value := strings.TrimSpace(os.Getenv(key)); value != "" {
			return value, nil
		}
	}
	return "", fmt.Errorf("missing task context; set %s or pass an explicit ID", strings.Join(keys, " or "))
}

func currentTaskID(explicit string) (string, error) {
	return contextValue(explicit, "AGENTSCOPE_TASK_ID")
}

func currentIssueID(explicit string) (string, error) {
	return contextValue(explicit, "AGENTSCOPE_ISSUE_ID")
}

func currentTeamID(explicit string) (string, error) {
	return contextValue(explicit, "AGENTSCOPE_TEAM_ID")
}

func optionalIDArg(args []string) string {
	if len(args) == 0 {
		return ""
	}
	return args[0]
}

func taskContextCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "context [TASK_ID]",
		Short: "Read the authoritative task-scoped collaboration context",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			id, err := currentTaskID(optionalIDArg(args))
			if err != nil {
				return err
			}
			return printTaskContextResponse(doTaskAPI(http.MethodGet, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/context", nil, agentTaskToken()))
		},
	}
}

func printTaskContextResponse(resp *http.Response, err error) error {
	if err != nil || resp == nil || resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return printResponse(resp, err)
	}
	body, err := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if err != nil {
		return err
	}
	var payload map[string]any
	if json.Unmarshal(body, &payload) == nil {
		delete(payload, "taskToken")
		body, _ = json.Marshal(payload)
	}
	resp.Body = io.NopCloser(bytes.NewReader(body))
	resp.ContentLength = int64(len(body))
	return printResponse(resp, nil)
}

func issueCurrentCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "current",
		Short: "Read the Issue bound to the current AgentTask",
		Args:  cobra.NoArgs,
		RunE: func(*cobra.Command, []string) error {
			id, err := currentIssueID("")
			if err != nil {
				return err
			}
			return printResponse(doTaskAPI(http.MethodGet, "/api/v1/issues/"+url.PathEscape(id), nil, agentTaskToken()))
		},
	}
}

func taskCommentActionCmd(action string) *cobra.Command {
	var requestFile, content, contentFile, parent, token string
	var mentions []string
	cmd := &cobra.Command{
		Use:   action + " [TASK_ID]",
		Short: map[string]string{"progress": "Post durable progress for the current AgentTask", "respond": "Post an attributable result for the current AgentTask"}[action],
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			id, err := currentTaskID(optionalIDArg(args))
			if err != nil {
				return err
			}
			var encoded []byte
			if requestFile != "" {
				if content != "" || contentFile != "" || parent != "" || len(mentions) > 0 {
					return fmt.Errorf("--file cannot be combined with content, parent, or mention flags")
				}
				encoded, err = readYAMLAsJSON(requestFile)
			} else {
				content, err = readContent(content, contentFile)
				if err == nil {
					body := map[string]any{"content": content}
					if parent != "" {
						body["parentId"] = parent
					}
					var parsed []map[string]string
					parsed, err = parseMentionTargets(mentions)
					if len(parsed) > 0 {
						body["mentions"] = parsed
					}
					encoded = jsonBody(body)
				}
			}
			if err != nil {
				return err
			}
			return printResponse(doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/"+action, encoded, token))
		},
	}
	cmd.Flags().StringVarP(&requestFile, "file", "f", "", "Full request YAML or JSON")
	cmd.Flags().StringVar(&content, "content", "", "Short content (prefer --content-file for Agent-authored text)")
	cmd.Flags().StringVar(&contentFile, "content-file", "", "UTF-8 file containing the content")
	cmd.Flags().StringVar(&parent, "parent", "", "Parent Comment ID")
	cmd.Flags().StringSliceVar(&mentions, "mention", nil, "Mention target type:ref (repeatable)")
	cmd.Flags().StringVar(&token, "task-token", agentTaskToken(), "Task-scoped token")
	return cmd
}

func taskChildCmd() *cobra.Command {
	var file, token string
	cmd := &cobra.Command{
		Use:   "child [TASK_ID]",
		Short: "Delegate child work from the current Team leader task",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			id, err := currentTaskID(optionalIDArg(args))
			if err != nil {
				return err
			}
			body, err := readYAMLAsJSON(file)
			if err != nil {
				return err
			}
			return printResponse(doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/children", body, token))
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Child Issue YAML or JSON")
	cmd.Flags().StringVar(&token, "task-token", agentTaskToken(), "Task-scoped token")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func taskCompleteCmd() *cobra.Command {
	var file, summary, resultFile, token string
	cmd := &cobra.Command{
		Use:   "complete [TASK_ID]",
		Short: "Complete the current AgentTask and reconcile its inputs",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			id, err := currentTaskID(optionalIDArg(args))
			if err != nil {
				return err
			}
			body := []byte(`{}`)
			if file != "" {
				body, err = readYAMLAsJSON(file)
			} else {
				payload := map[string]any{"summary": summary}
				if resultFile != "" {
					result, readErr := readYAMLAsJSON(resultFile)
					if readErr != nil {
						return readErr
					}
					payload["result"] = json.RawMessage(result)
				}
				body = jsonBody(payload)
			}
			if err != nil {
				return err
			}
			return printResponse(doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/complete", body, token))
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Full completion request YAML or JSON")
	cmd.Flags().StringVar(&summary, "summary", "", "Completion summary")
	cmd.Flags().StringVar(&resultFile, "result-file", "", "Result YAML or JSON")
	cmd.Flags().StringVar(&token, "task-token", agentTaskToken(), "Task-scoped token")
	return cmd
}

func taskFailCmd() *cobra.Command {
	var file, code, message, token string
	cmd := &cobra.Command{
		Use:   "fail [TASK_ID]",
		Short: "Fail the current AgentTask with a durable error",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			id, err := currentTaskID(optionalIDArg(args))
			if err != nil {
				return err
			}
			var body []byte
			if file != "" {
				body, err = readYAMLAsJSON(file)
			} else {
				if strings.TrimSpace(code) == "" {
					return fmt.Errorf("--code is required when --file is not used")
				}
				body = jsonBody(map[string]any{"code": code, "message": message})
			}
			if err != nil {
				return err
			}
			return printResponse(doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/fail", body, token))
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Full failure request YAML or JSON")
	cmd.Flags().StringVar(&code, "code", "", "Stable failure code")
	cmd.Flags().StringVar(&message, "message", "", "Failure message")
	cmd.Flags().StringVar(&token, "task-token", agentTaskToken(), "Task-scoped token")
	return cmd
}

func taskRunCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "run", Short: "Operate on the Run containing the current AgentTask"}
	cmd.AddCommand(taskRunGetCmd("get", ""), taskRunGetCmd("graph", "/graph"), taskRunGetCmd("artifacts", "/artifacts"),
		taskRunNodeCompleteCmd(), taskRunNodeFailCmd(), taskRunReplanCmd(), taskRunSignalCmd())
	return cmd
}

func taskRunGetCmd(use, suffix string) *cobra.Command {
	return &cobra.Command{Use: use + " [TASK_ID]", Args: cobra.MaximumNArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		id, err := currentTaskID(optionalIDArg(args))
		if err != nil {
			return err
		}
		return printResponse(doTaskAPI(http.MethodGet, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/run"+suffix, nil, agentTaskToken()))
	}}
}

func taskRunNodeCompleteCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "node-complete [TASK_ID]", Args: cobra.MaximumNArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		id, err := currentTaskID(optionalIDArg(args))
		if err != nil {
			return err
		}
		output := json.RawMessage(`{}`)
		if file != "" {
			output, err = readYAMLAsJSON(file)
			if err != nil {
				return err
			}
		}
		return printResponse(doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/run/node/complete", jsonBody(map[string]any{"output": output}), agentTaskToken()))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Coordinator output YAML or JSON")
	return cmd
}

func taskRunNodeFailCmd() *cobra.Command {
	var code, message string
	cmd := &cobra.Command{Use: "node-fail [TASK_ID]", Args: cobra.MaximumNArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		id, err := currentTaskID(optionalIDArg(args))
		if err != nil {
			return err
		}
		return printResponse(doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/run/node/fail", jsonBody(map[string]any{"code": code, "message": message}), agentTaskToken()))
	}}
	cmd.Flags().StringVar(&code, "code", "", "Stable failure code")
	cmd.Flags().StringVar(&message, "message", "", "Failure message")
	_ = cmd.MarkFlagRequired("code")
	return cmd
}

func taskRunReplanCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "replan [TASK_ID]", Args: cobra.MaximumNArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		id, err := currentTaskID(optionalIDArg(args))
		if err != nil {
			return err
		}
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/run/replan", body, agentTaskToken()))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Dynamic node YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func taskRunSignalCmd() *cobra.Command {
	var file, key string
	cmd := &cobra.Command{Use: "signal NAME [TASK_ID]", Args: cobra.RangeArgs(1, 2), RunE: func(_ *cobra.Command, args []string) error {
		explicit := ""
		if len(args) == 2 {
			explicit = args[1]
		}
		id, err := currentTaskID(explicit)
		if err != nil {
			return err
		}
		payload := json.RawMessage(`null`)
		if file != "" {
			payload, err = readYAMLAsJSON(file)
			if err != nil {
				return err
			}
		}
		body := jsonBody(map[string]any{"idempotencyKey": key, "payload": payload})
		return printResponse(doTaskAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(id)+"/run/signals/"+url.PathEscape(args[0]), body, agentTaskToken()))
	}}
	cmd.Flags().StringVar(&key, "idempotency-key", "", "Unique signal delivery key")
	cmd.Flags().StringVarP(&file, "file", "f", "", "Signal payload YAML or JSON")
	_ = cmd.MarkFlagRequired("idempotency-key")
	return cmd
}

func teamCurrentCmd() *cobra.Command {
	return &cobra.Command{Use: "current", Short: "Read the Team bound to the current AgentTask", Args: cobra.NoArgs, RunE: func(*cobra.Command, []string) error {
		id, err := currentTeamID("")
		if err != nil {
			return err
		}
		return printResponse(doTaskAPI(http.MethodGet, "/api/v1/teams/"+url.PathEscape(id), nil, agentTaskToken()))
	}}
}

func readContent(inline, path string) (string, error) {
	if inline != "" && path != "" {
		return "", fmt.Errorf("use only one of --content and --content-file")
	}
	if path != "" {
		data, err := os.ReadFile(path)
		if err != nil {
			return "", err
		}
		inline = string(data)
	}
	if strings.TrimSpace(inline) == "" {
		return "", fmt.Errorf("--content or --content-file is required")
	}
	return inline, nil
}

func parseMentionTargets(values []string) ([]map[string]string, error) {
	targets := make([]map[string]string, 0, len(values))
	for _, raw := range values {
		kind, ref, ok := strings.Cut(raw, ":")
		if !ok || ref == "" || kind != "human" && kind != "agent" && kind != "team" {
			return nil, fmt.Errorf("invalid mention %q; expected human:REF, agent:REF, or team:REF", raw)
		}
		targets = append(targets, map[string]string{"type": kind, "ref": ref})
	}
	return targets, nil
}
