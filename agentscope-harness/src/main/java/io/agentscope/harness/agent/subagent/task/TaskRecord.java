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
package io.agentscope.harness.agent.subagent.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Persistent metadata for a background subagent task, stored as JSON in the workspace under
 * {@code agents/<parentAgentId>/tasks/<parentSessionId>.json}.
 *
 * <p>This is the authoritative truth source for task state. In-memory {@link BackgroundTask}
 * handles are a local performance overlay; {@code TaskRecord} survives across JVM restarts and
 * request migrations in distributed deployments.
 *
 * <p>All fields use Jackson for JSON serialization. Unknown fields are silently ignored to
 * allow forward-compatible schema evolution.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRecord {

    private String taskId;
    private String subAgentId;
    private String parentAgentId;
    private String parentSessionId;
    private String subSessionId;
    private TaskStatus status;
    private String result;
    private String errorMessage;
    private boolean cancelRequested;
    private Instant createdAt;
    private Instant lastCheckedAt;
    private Instant lastUpdatedAt;

    public TaskRecord() {}

    public TaskRecord(
            String taskId,
            String subAgentId,
            String parentAgentId,
            String parentSessionId,
            String subSessionId) {
        this.taskId = taskId;
        this.subAgentId = subAgentId;
        this.parentAgentId = parentAgentId;
        this.parentSessionId = parentSessionId;
        this.subSessionId = subSessionId;
        this.status = TaskStatus.PENDING;
        this.cancelRequested = false;
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastCheckedAt = now;
        this.lastUpdatedAt = now;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSubAgentId() {
        return subAgentId;
    }

    public void setSubAgentId(String subAgentId) {
        this.subAgentId = subAgentId;
    }

    public String getParentAgentId() {
        return parentAgentId;
    }

    public void setParentAgentId(String parentAgentId) {
        this.parentAgentId = parentAgentId;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(String parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public String getSubSessionId() {
        return subSessionId;
    }

    public void setSubSessionId(String subSessionId) {
        this.subSessionId = subSessionId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    /** Convenience: update lastUpdatedAt to now. */
    public void touch() {
        this.lastUpdatedAt = Instant.now();
    }

    /** Convenience: update lastCheckedAt to now. */
    public void touchChecked() {
        this.lastCheckedAt = Instant.now();
    }
}
