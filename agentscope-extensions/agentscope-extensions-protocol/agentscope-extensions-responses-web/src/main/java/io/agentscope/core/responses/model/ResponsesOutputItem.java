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
package io.agentscope.core.responses.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Output item in a Responses API response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesOutputItem {

    private String id;
    private String type;
    private String role;
    private String status;
    private List<ResponsesContentPart> content;

    @JsonProperty("call_id")
    private String callId;

    private String name;
    private String arguments;

    /**
     * Create a completed assistant message output item.
     *
     * @param id Output item ID
     * @param text Assistant text
     * @return Message output item
     */
    public static ResponsesOutputItem message(String id, String text) {
        return message(id, text, "completed");
    }

    /**
     * Create an assistant message output item.
     *
     * @param id Output item ID
     * @param text Assistant text
     * @param status Item status, such as {@code in_progress} or {@code completed}
     * @return Message output item
     */
    public static ResponsesOutputItem message(String id, String text, String status) {
        ResponsesOutputItem item = new ResponsesOutputItem();
        item.setId(id);
        item.setType("message");
        item.setRole("assistant");
        item.setStatus(status);
        item.setContent(List.of(ResponsesContentPart.outputText(text)));
        return item;
    }

    /**
     * Create a completed function-call output item.
     *
     * @param id Output item ID
     * @param callId Tool call ID used by the model and follow-up tool output
     * @param name Function name
     * @param arguments JSON argument string
     * @return Function-call output item
     */
    public static ResponsesOutputItem functionCall(
            String id, String callId, String name, String arguments) {
        return functionCall(id, callId, name, arguments, "completed");
    }

    /**
     * Create a function-call output item.
     *
     * @param id Output item ID
     * @param callId Tool call ID used by the model and follow-up tool output
     * @param name Function name
     * @param arguments JSON argument string
     * @param status Item status, such as {@code in_progress} or {@code completed}
     * @return Function-call output item
     */
    public static ResponsesOutputItem functionCall(
            String id, String callId, String name, String arguments, String status) {
        ResponsesOutputItem item = new ResponsesOutputItem();
        item.setId(id);
        item.setType("function_call");
        item.setCallId(callId);
        item.setName(name);
        item.setArguments(arguments);
        item.setStatus(status);
        return item;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<ResponsesContentPart> getContent() {
        return content;
    }

    public void setContent(List<ResponsesContentPart> content) {
        this.content = content;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }
}
