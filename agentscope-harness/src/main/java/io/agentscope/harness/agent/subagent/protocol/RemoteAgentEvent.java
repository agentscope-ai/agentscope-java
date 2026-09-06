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
package io.agentscope.harness.agent.subagent.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Stable Protocol DTO for remote subagent event streaming.
 *
 * <p>Fields are optional per event type; unknown fields are ignored for forward compatibility.
 *
 * <p>Besides the flat per-type fields, an event may carry {@link #getPayload()} — the source {@link
 * io.agentscope.core.event.AgentEvent} serialized in full. Clients that understand it restore the
 * original event (ids, timestamps, metadata and all); older ones keep reading the flat fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RemoteAgentEvent {

    private long seq;
    private RemoteEventType type;
    private String taskId;
    private String agentId;
    private String timestamp;
    private String text;
    private String toolCallId;
    private String toolName;
    private String toolInput;
    private List<RemotePendingConfirm> pendingConfirms;
    private String status;
    private String error;
    private String eventType;
    private String payload;

    public RemoteAgentEvent() {}

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public RemoteEventType getType() {
        return type;
    }

    public void setType(RemoteEventType type) {
        this.type = type;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolInput() {
        return toolInput;
    }

    public void setToolInput(String toolInput) {
        this.toolInput = toolInput;
    }

    public List<RemotePendingConfirm> getPendingConfirms() {
        return pendingConfirms;
    }

    public void setPendingConfirms(List<RemotePendingConfirm> pendingConfirms) {
        this.pendingConfirms = pendingConfirms;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    /**
     * Name of the source {@link io.agentscope.core.event.AgentEventType}, e.g.
     * {@code MODEL_CALL_END}. Lets a client filter or log events without deserializing {@link
     * #getPayload()}.
     */
    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    /**
     * The source {@link io.agentscope.core.event.AgentEvent} as JSON, or {@code null} for events
     * the server synthesizes itself ({@code STATUS}, {@code RUN_ERROR}) and for events whose
     * serialization failed.
     */
    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
