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
