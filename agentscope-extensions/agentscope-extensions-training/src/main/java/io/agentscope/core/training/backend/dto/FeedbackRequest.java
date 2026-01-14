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
