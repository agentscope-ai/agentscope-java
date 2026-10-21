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
import java.util.Objects;

/**
 * A single remote tool call awaiting user confirmation (HITL).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RemotePendingConfirm {

    private String toolCallId;
    private String toolName;
    private String toolInputJson;

    public RemotePendingConfirm() {}

    public RemotePendingConfirm(String toolCallId, String toolName, String toolInputJson) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolInputJson = toolInputJson;
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

    public String getToolInputJson() {
        return toolInputJson;
    }

    public void setToolInputJson(String toolInputJson) {
        this.toolInputJson = toolInputJson;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RemotePendingConfirm that)) {
            return false;
        }
        return Objects.equals(toolCallId, that.toolCallId)
                && Objects.equals(toolName, that.toolName)
                && Objects.equals(toolInputJson, that.toolInputJson);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolCallId, toolName, toolInputJson);
    }
}
