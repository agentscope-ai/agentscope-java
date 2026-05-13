/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Trinity Commit API Request
 */
public class CommitRequest {
    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("time_threshold")
    private Long timeThreshold;

    private CommitRequest(Builder builder) {
        this.taskId = builder.taskId;
        this.runId = builder.runId;
        this.timeThreshold = builder.timeThreshold;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public String getTaskId() {
        return taskId;
    }

    public String getRunId() {
        return runId;
    }

    public Long getTimeThreshold() {
        return timeThreshold;
    }

    public static class Builder {
        private String taskId;
        private String runId;
        private Long timeThreshold;

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder timeThreshold(Long timeThreshold) {
            this.timeThreshold = timeThreshold;
            return this;
        }

        public CommitRequest build() {
            return new CommitRequest(this);
        }
    }
}
