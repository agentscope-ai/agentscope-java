/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.coding.reviewer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single reviewer finding — mirrors open-swe's {@code Finding} model in {@code
 * agent/reviewer_findings.py}.
 *
 * <p>Findings are persisted in {@code SqliteBaseStore} namespace {@code ["findings", thread_id]}
 * and batched into a single GitHub PR review by {@link GitHubReviewPublisher}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Finding {

    @JsonProperty("id")
    private String id;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("category")
    private String category;

    @JsonProperty("file")
    private String file;

    @JsonProperty("start_line")
    private Integer startLine;

    @JsonProperty("end_line")
    private Integer endLine;

    @JsonProperty("description")
    private String description;

    @JsonProperty("suggestion")
    private String suggestion;

    @JsonProperty("status")
    private String status = "open";

    @JsonProperty("github_comment_id")
    private Long githubCommentId;

    @JsonProperty("note")
    private String note;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public void setStartLine(Integer startLine) {
        this.startLine = startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public void setEndLine(Integer endLine) {
        this.endLine = endLine;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getGithubCommentId() {
        return githubCommentId;
    }

    public void setGithubCommentId(Long githubCommentId) {
        this.githubCommentId = githubCommentId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    @Override
    public String toString() {
        return "Finding{"
                + "id='"
                + id
                + "', severity='"
                + severity
                + "', file='"
                + file
                + ":"
                + startLine
                + "', status='"
                + status
                + "'}";
    }
}
