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
import java.util.List;

/**
 * Trinity Feedback API Request
 */
public class FeedbackRequest {
    @JsonProperty("msg_ids")
    private List<String> msgIds;

    @JsonProperty("reward")
    private Double reward;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("run_id")
    private String runId;

    private FeedbackRequest(Builder builder) {
        this.msgIds = builder.msgIds;
        this.reward = builder.reward;
        this.taskId = builder.taskId;
        this.runId = builder.runId;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public List<String> getMsgIds() {
        return msgIds;
    }

    public Double getReward() {
        return reward;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getRunId() {
        return runId;
    }

    public static class Builder {
        private List<String> msgIds;
        private Double reward;
        private String taskId;
        private String runId;

        public Builder msgIds(List<String> msgIds) {
            this.msgIds = msgIds;
            return this;
        }

        public Builder reward(Double reward) {
            this.reward = reward;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public FeedbackRequest build() {
            return new FeedbackRequest(this);
        }
    }
}
