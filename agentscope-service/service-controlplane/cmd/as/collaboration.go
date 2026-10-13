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
	"bytes"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"
)

func issueCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "issue", Short: "Work with Issues and discussions"}
	cmd.AddCommand(issueCreateCmd(), issueListCmd(), issueGetCmd(), issueCurrentCmd(), issueUpdateCmd(), issueAssignCmd(), issueTransitionCmd(), issueReviewCmd("accept"), issueReviewCmd("reject"), issueReviewCmd("reopen"), issueReviewCmd("archive"), issueSummaryCmd(), issueExportCmd(), issueCommentCmd(), issueChildCmd())
	return cmd
}
func issueCreateCmd() *cobra.Command {
	return filePostCommand("create", "Issue YAML or JSON", "/api/v1/issues")
}
func issueUpdateCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "update ISSUE_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doAPI(http.MethodPatch, "/api/v1/issues/"+url.PathEscape(args[0]), body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Patch YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
func issueListCmd() *cobra.Command {
	var status, search string
	var archived bool
	cmd := &cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		q := url.Values{"tenant": []string{tenant}, "namespace": []string{namespace}}
		if status != "" {
			q.Set("status", status)
		}
		if search != "" {
			q.Set("search", search)
		}
		if archived {
			q.Set("archived", "true")
		}
		return printResponse(doAPI(http.MethodGet, "/api/v1/issues?"+q.Encode(), nil))
	}}
	cmd.Flags().StringVar(&status, "status", "", "Issue status")
	cmd.Flags().StringVar(&search, "search", "", "Full-text search")
	cmd.Flags().BoolVar(&archived, "archived", false, "List archived Issues")
	return cmd
}

func issueReviewCmd(action string) *cobra.Command {
	var version int64
	var reason string
	cmd := &cobra.Command{Use: action + " ISSUE_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodPost, "/api/v1/issues/"+url.PathEscape(args[0])+"/"+action,
			jsonBody(map[string]any{"expectedVersion": version, "reason": reason})))
	}}
	cmd.Flags().Int64Var(&version, "expected-version", 0, "Optimistic-lock version")
	cmd.Flags().StringVar(&reason, "reason", "", "Decision reason")
	return cmd
}

func issueSummaryCmd() *cobra.Command {
	return &cobra.Command{Use: "summary ISSUE_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/issues/"+url.PathEscape(args[0])+"/summary", nil))
	}}
}

func issueExportCmd() *cobra.Command {
	return &cobra.Command{Use: "export ISSUE_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodGet, "/api/v1/issues/"+url.PathEscape(args[0])+"/export", nil))
	}}
}
func issueGetCmd() *cobra.Command {
	return &cobra.Command{Use: "get ISSUE_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAgentOrHumanAPI(http.MethodGet, "/api/v1/issues/"+url.PathEscape(args[0]), nil))
	}}
}
func issueAssignCmd() *cobra.Command {
	var kind, ref string
	var version int64
	cmd := &cobra.Command{Use: "assign ISSUE_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodPost, "/api/v1/issues/"+url.PathEscape(args[0])+"/assign", jsonBody(map[string]any{"assigneeType": kind, "assigneeRef": ref, "expectedVersion": version})))
	}}
	cmd.Flags().StringVar(&kind, "type", "", "human, agent, or team")
	cmd.Flags().StringVar(&ref, "ref", "", "Assignee reference")
	cmd.Flags().Int64Var(&version, "expected-version", 0, "Optimistic-lock version")
	_ = cmd.MarkFlagRequired("type")
	_ = cmd.MarkFlagRequired("ref")
	return cmd
}
func issueTransitionCmd() *cobra.Command {
	var status, reason string
	var version int64
	cmd := &cobra.Command{Use: "transition ISSUE_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodPost, "/api/v1/issues/"+url.PathEscape(args[0])+"/transition", jsonBody(map[string]any{"status": status, "reason": reason, "expectedVersion": version})))
	}}
	cmd.Flags().StringVar(&status, "status", "", "Target status")
	cmd.Flags().StringVar(&reason, "reason", "", "Reason")
	cmd.Flags().Int64Var(&version, "expected-version", 0, "Optimistic-lock version")
	_ = cmd.MarkFlagRequired("status")
	return cmd
}
func issueChildCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: "child ISSUE_ID", Short: "Create a child Issue", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doAPI(http.MethodPost, "/api/v1/issues/"+url.PathEscape(args[0])+"/children", body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", "Child Issue YAML or JSON")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
func issueCommentCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "comment"}
	cmd.AddCommand(issueCommentListCmd(), issueCommentAddCmd(), issueCommentResolveCmd())
	return cmd
}
func issueCommentListCmd() *cobra.Command {
	var rootsOnly, summary bool
	var threadID, cursor string
	var tail, limit int
	cmd := &cobra.Command{Use: "list [ISSUE_ID]", Args: cobra.MaximumNArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		id, err := currentIssueID(optionalIDArg(args))
		if err != nil {
			return err
		}
		query := url.Values{}
		if rootsOnly {
			query.Set("rootsOnly", "true")
		}
		if summary {
			query.Set("summary", "true")
		}
		if threadID != "" {
			query.Set("threadId", threadID)
		}
		if cursor != "" {
			query.Set("cursor", cursor)
		}
		if tail > 0 {
			query.Set("tail", fmt.Sprint(tail))
		}
		if limit > 0 {
			query.Set("limit", fmt.Sprint(limit))
		}
		path := "/api/v1/issues/" + url.PathEscape(id) + "/comments"
		if encoded := query.Encode(); encoded != "" {
			path += "?" + encoded
		}
		return printResponse(doAgentOrHumanAPI(http.MethodGet, path, nil))
	}}
	cmd.Flags().BoolVar(&rootsOnly, "roots-only", false, "Return only discussion roots")
	cmd.Flags().BoolVar(&summary, "summary", false, "Include a compact discussion summary")
	cmd.Flags().StringVar(&threadID, "thread", "", "Return one discussion thread")
	cmd.Flags().StringVar(&cursor, "cursor", "", "Continue from a response cursor")
	cmd.Flags().IntVar(&tail, "tail", 0, "Return the newest N comments in a thread")
	cmd.Flags().IntVar(&limit, "limit", 0, "Maximum comments to return")
	return cmd
}
func issueCommentAddCmd() *cobra.Command {
	var content, contentFile, parent string
	var mentions []string
	cmd := &cobra.Command{Use: "add [ISSUE_ID]", Args: cobra.MaximumNArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		id, err := currentIssueID(optionalIDArg(args))
		if err != nil {
			return err
		}
		content, err = readContent(content, contentFile)
		if err != nil {
			return err
		}
		body := map[string]any{"content": content}
		if parent != "" {
			body["parentId"] = parent
		}
		if targets, parseErr := parseMentionTargets(mentions); parseErr != nil {
			return parseErr
		} else if len(targets) > 0 {
			body["mentions"] = targets
		}
		return printResponse(doAgentOrHumanAPI(http.MethodPost, "/api/v1/issues/"+url.PathEscape(id)+"/comments", jsonBody(body)))
	}}
	cmd.Flags().StringVar(&content, "content", "", "Short comment content")
	cmd.Flags().StringVar(&contentFile, "content-file", "", "UTF-8 file containing the comment")
	cmd.Flags().StringVar(&parent, "parent", "", "Parent Comment ID")
	cmd.Flags().StringSliceVar(&mentions, "mention", nil, "Mention target type:ref (repeatable)")
	return cmd
}
func issueCommentResolveCmd() *cobra.Command {
	var version int64
	cmd := &cobra.Command{Use: "resolve ISSUE_ID COMMENT_ID", Args: cobra.ExactArgs(2), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodPost, "/api/v1/issues/"+url.PathEscape(args[0])+"/comments/"+url.PathEscape(args[1])+"/resolve", jsonBody(map[string]any{"resolved": true, "expectedVersion": version})))
	}}
	cmd.Flags().Int64Var(&version, "expected-version", 0, "Optimistic-lock version")
	return cmd
}

func taskCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "task", Short: "Work with AgentTasks"}
	cmd.AddCommand(taskListCmd(), taskGetCmd(), taskContextCmd(), taskSimpleActionCmd("retry"), taskSimpleActionCmd("cancel"),
		taskCommentActionCmd("progress"), taskCommentActionCmd("respond"), taskChildCmd(), taskCompleteCmd(), taskFailCmd(), taskRunCmd())
	return cmd
}
func taskListCmd() *cobra.Command {
	return &cobra.Command{Use: "list", RunE: func(*cobra.Command, []string) error {
		q := url.Values{"tenant": []string{tenant}, "namespace": []string{namespace}}
		return printResponse(doAPI(http.MethodGet, "/api/v1/agent-tasks?"+q.Encode(), nil))
	}}
}
func taskSimpleActionCmd(action string) *cobra.Command {
	var version int64
	cmd := &cobra.Command{Use: action + " TASK_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		return printResponse(doAPI(http.MethodPost, "/api/v1/agent-tasks/"+url.PathEscape(args[0])+"/"+action, jsonBody(map[string]any{"expectedVersion": version})))
	}}
	cmd.Flags().Int64Var(&version, "expected-version", 0, "Optimistic-lock version")
	return cmd
}
func taskGetCmd() *cobra.Command {
	return &cobra.Command{Use: "get [TASK_ID]", Args: cobra.MaximumNArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		id, err := currentTaskID(optionalIDArg(args))
		if err != nil {
			return err
		}
		return printResponse(doAgentOrHumanAPI(http.MethodGet, "/api/v1/agent-tasks/"+url.PathEscape(id), nil))
	}}
}
func artifactCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "artifact"}
	cmd.AddCommand(artifactUploadCmd(), artifactDownloadCmd())
	return cmd
}
func artifactUploadCmd() *cobra.Command {
	var artifactTenant, targetType, targetRef, taskID, taskToken string
	cmd := &cobra.Command{Use: "upload FILE", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		if taskToken == "" {
			taskToken = agentTaskToken()
		}
		if taskID == "" && taskToken != "" {
			taskID = os.Getenv("AGENTSCOPE_TASK_ID")
		}
		if targetRef == "" && taskToken != "" {
			targetRef = os.Getenv("AGENTSCOPE_ISSUE_ID")
		}
		file, err := os.Open(args[0])
		if err != nil {
			return err
		}
		defer file.Close()
		var body bytes.Buffer
		writer := multipart.NewWriter(&body)
		part, err := writer.CreateFormFile("file", filepath.Base(args[0]))
		if err != nil {
			return err
		}
		if _, err = io.Copy(part, file); err != nil {
			return err
		}
		if artifactTenant == "" {
			artifactTenant = tenant
		}
		for key, value := range map[string]string{"tenant": artifactTenant, "namespace": namespace, "targetType": targetType, "targetRef": targetRef, "sourceTaskId": taskID} {
			if value != "" {
				_ = writer.WriteField(key, value)
			}
		}
		_ = writer.Close()
		req, err := http.NewRequest(http.MethodPost, apiEndpoint+"/api/v1/artifacts/uploads", &body)
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", writer.FormDataContentType())
		if taskToken != "" {
			req.Header.Set("X-Agent-Task-Token", taskToken)
		}
		return printResponse(newAPIClient().Do(req))
	}}
	cmd.Flags().StringVar(&artifactTenant, "artifact-tenant", "", "Artifact tenant (defaults to global --tenant)")
	cmd.Flags().StringVar(&targetType, "target-type", "issue", "issue or agent_task")
	cmd.Flags().StringVar(&targetRef, "target-ref", "", "Target ID")
	cmd.Flags().StringVar(&taskID, "task-id", "", "Source AgentTask ID")
	cmd.Flags().StringVar(&taskToken, "task-token", agentTaskToken(), "Task-scoped token")
	return cmd
}
func artifactDownloadCmd() *cobra.Command {
	var output, taskID, taskToken string
	cmd := &cobra.Command{Use: "download ARTIFACT_ID", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		if taskToken == "" {
			taskToken = agentTaskToken()
		}
		if taskID == "" && taskToken != "" {
			taskID = os.Getenv("AGENTSCOPE_TASK_ID")
		}
		path := "/api/v1/artifacts/" + url.PathEscape(args[0]) + "/download"
		if taskID != "" {
			path += "?taskId=" + url.QueryEscape(taskID)
		}
		req, err := http.NewRequest(http.MethodPost, apiEndpoint+path, nil)
		if err != nil {
			return err
		}
		if taskToken != "" {
			req.Header.Set("X-Agent-Task-Token", taskToken)
		}
		resp, err := newAPIClient().Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		if resp.StatusCode/100 != 2 {
			return fmt.Errorf("download failed: %s", resp.Status)
		}
		data := new(bytes.Buffer)
		_, err = data.ReadFrom(resp.Body)
		if err != nil {
			return err
		}
		return os.WriteFile(output, data.Bytes(), 0o600)
	}}
	cmd.Flags().StringVarP(&output, "output", "o", "artifact.bin", "Output path")
	cmd.Flags().StringVar(&taskID, "task-id", "", "AgentTask ID")
	cmd.Flags().StringVar(&taskToken, "task-token", agentTaskToken(), "Task-scoped token")
	return cmd
}

func filePostCommand(use, description, path string) *cobra.Command {
	var file string
	cmd := &cobra.Command{Use: use, RunE: func(*cobra.Command, []string) error {
		body, err := readYAMLAsJSON(file)
		if err != nil {
			return err
		}
		return printResponse(doAPI(http.MethodPost, path, body))
	}}
	cmd.Flags().StringVarP(&file, "file", "f", "", description)
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
