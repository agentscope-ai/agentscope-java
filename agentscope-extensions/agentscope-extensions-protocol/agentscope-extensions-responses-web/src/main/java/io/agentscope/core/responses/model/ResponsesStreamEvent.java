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

/** A single Responses API streaming event payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesStreamEvent {

    private String type;

    @JsonProperty("sequence_number")
    private Long sequenceNumber;

    @JsonProperty("response_id")
    private String responseId;

    private ResponsesResponse response;

    @JsonProperty("output_index")
    private Integer outputIndex;

    @JsonProperty("content_index")
    private Integer contentIndex;

    @JsonProperty("item_id")
    private String itemId;

    private ResponsesOutputItem item;

    private ResponsesContentPart part;
    private String delta;
    private String text;
    private String arguments;

    @JsonProperty("call_id")
    private String callId;

    private String name;
    private ResponsesError error;

    /**
     * Create an event whose payload is a full response object.
     *
     * @param type Responses event type
     * @param response Response payload
     * @return Stream event
     */
    public static ResponsesStreamEvent responseEvent(String type, ResponsesResponse response) {
        ResponsesStreamEvent event = new ResponsesStreamEvent();
        event.setType(type);
        event.setResponse(response);
        return event;
    }

    /**
     * Create an output-item lifecycle event.
     *
     * @param type Responses event type
     * @param outputIndex Output item index
     * @param item Output item payload
     * @return Stream event
     */
    public static ResponsesStreamEvent outputItemEvent(
            String type, Integer outputIndex, ResponsesOutputItem item) {
        ResponsesStreamEvent event = new ResponsesStreamEvent();
        event.setType(type);
        event.setOutputIndex(outputIndex);
        event.setItem(item);
        return event;
    }

    /**
     * Create a content-part lifecycle event.
     *
     * @param type Responses event type
     * @param outputIndex Output item index
     * @param contentIndex Content part index within the output item
     * @param itemId Output item ID
     * @param part Content part payload
     * @return Stream event
     */
    public static ResponsesStreamEvent contentPartEvent(
            String type,
            Integer outputIndex,
            Integer contentIndex,
            String itemId,
            ResponsesContentPart part) {
        ResponsesStreamEvent event = new ResponsesStreamEvent();
        event.setType(type);
        event.setOutputIndex(outputIndex);
        event.setContentIndex(contentIndex);
        event.setItemId(itemId);
        event.setPart(part);
        return event;
    }

    /**
     * Create an output text delta event.
     *
     * @param type Responses event type
     * @param outputIndex Output item index
     * @param contentIndex Content part index within the output item
     * @param itemId Output item ID
     * @param delta Incremental text delta
     * @return Stream event
     */
    public static ResponsesStreamEvent textDelta(
            String type, Integer outputIndex, Integer contentIndex, String itemId, String delta) {
        ResponsesStreamEvent event =
                contentPartEvent(type, outputIndex, contentIndex, itemId, null);
        event.setDelta(delta);
        return event;
    }

    /**
     * Create an output text done event.
     *
     * @param type Responses event type
     * @param outputIndex Output item index
     * @param contentIndex Content part index within the output item
     * @param itemId Output item ID
     * @param text Final accumulated text
     * @return Stream event
     */
    public static ResponsesStreamEvent textDone(
            String type, Integer outputIndex, Integer contentIndex, String itemId, String text) {
        ResponsesStreamEvent event =
                contentPartEvent(type, outputIndex, contentIndex, itemId, null);
        event.setText(text);
        return event;
    }

    /**
     * Create a function-call arguments done event.
     *
     * @param outputIndex Output item index
     * @param itemId Function-call output item ID
     * @param arguments Final JSON arguments
     * @return Stream event
     */
    public static ResponsesStreamEvent argumentsDone(
            Integer outputIndex, String itemId, String arguments) {
        ResponsesStreamEvent event = new ResponsesStreamEvent();
        event.setType("response.function_call_arguments.done");
        event.setOutputIndex(outputIndex);
        event.setItemId(itemId);
        event.setArguments(arguments);
        return event;
    }

    /**
     * Create a function-call arguments delta event.
     *
     * @param outputIndex Output item index
     * @param itemId Function-call output item ID
     * @param delta Incremental JSON argument text
     * @return Stream event
     */
    public static ResponsesStreamEvent argumentsDelta(
            Integer outputIndex, String itemId, String delta) {
        ResponsesStreamEvent event = new ResponsesStreamEvent();
        event.setType("response.function_call_arguments.delta");
        event.setOutputIndex(outputIndex);
        event.setItemId(itemId);
        event.setDelta(delta);
        return event;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(Long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getResponseId() {
        return responseId;
    }

    public void setResponseId(String responseId) {
        this.responseId = responseId;
    }

    public ResponsesResponse getResponse() {
        return response;
    }

    public void setResponse(ResponsesResponse response) {
        this.response = response;
    }

    public Integer getOutputIndex() {
        return outputIndex;
    }

    public void setOutputIndex(Integer outputIndex) {
        this.outputIndex = outputIndex;
    }

    public Integer getContentIndex() {
        return contentIndex;
    }

    public void setContentIndex(Integer contentIndex) {
        this.contentIndex = contentIndex;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public ResponsesOutputItem getItem() {
        return item;
    }

    public void setItem(ResponsesOutputItem item) {
        this.item = item;
    }

    public ResponsesContentPart getPart() {
        return part;
    }

    public void setPart(ResponsesContentPart part) {
        this.part = part;
    }

    public String getDelta() {
        return delta;
    }

    public void setDelta(String delta) {
        this.delta = delta;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
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

    public ResponsesError getError() {
        return error;
    }

    public void setError(ResponsesError error) {
        this.error = error;
    }
}
