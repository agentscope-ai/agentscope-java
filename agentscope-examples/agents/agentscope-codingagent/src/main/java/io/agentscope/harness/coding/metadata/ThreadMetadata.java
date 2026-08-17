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
package io.agentscope.harness.coding.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-thread metadata stored in {@code SqliteBaseStore} namespace {@code ["threads", thread_id]}.
 *
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ThreadMetadata {

    @JsonProperty("thread_id")
    private String threadId;

    /** AgentStateStore kind: "coding" or "reviewer". */
    @JsonProperty("kind")
    private String kind;

    /** Encrypted GitHub token for this thread. */
    @JsonProperty("gh_token_enc")
    private String ghTokenEncrypted;

    /** Whether this thread is in watch mode (auto-re-review on push). */
    @JsonProperty("watch")
    private boolean watch;

    /** Last reviewed commit SHA (for incremental re-reviews). */
    @JsonProperty("last_reviewed_sha")
    private String lastReviewedSha;

    /** The PR URL being reviewed (for reviewer threads). */
    @JsonProperty("pr_url")
    private String prUrl;

    /** GitHub repo owner. */
    @JsonProperty("repo_owner")
    private String repoOwner;

    /** GitHub repo name. */
    @JsonProperty("repo_name")
    private String repoName;

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getGhTokenEncrypted() {
        return ghTokenEncrypted;
    }

    public void setGhTokenEncrypted(String ghTokenEncrypted) {
        this.ghTokenEncrypted = ghTokenEncrypted;
    }

    public boolean isWatch() {
        return watch;
    }

    public void setWatch(boolean watch) {
        this.watch = watch;
    }

    public String getLastReviewedSha() {
        return lastReviewedSha;
    }

    public void setLastReviewedSha(String lastReviewedSha) {
        this.lastReviewedSha = lastReviewedSha;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public String getRepoOwner() {
        return repoOwner;
    }

    public void setRepoOwner(String repoOwner) {
        this.repoOwner = repoOwner;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }
}
