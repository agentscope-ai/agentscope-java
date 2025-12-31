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
package io.agentscope.core.agent.test;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Mock Model implementation for testing.
 *
 * This mock provides configurable responses and allows tests to verify
 * model interactions without making actual API calls.
 */
public class MockModel implements Model {

    private final List<ChatResponse> responsesToReturn;
    private Function<List<Msg>, List<ChatResponse>> responseGenerator;
    private int callCount = 0;
    private List<Msg> lastMessages;
    private List<ToolSchema> lastTools;
    private GenerateOptions lastOptions;
    private boolean shouldThrowError = false;
    private String errorMessage = "Mock error";

    /**
     * Create a mock model with a simple text response.
     */
    public MockModel(String response) {
        this.responsesToReturn = new ArrayList<>();
        this.responsesToReturn.add(createTextResponse(response));
    }

    /**
     * Create a mock model with multiple responses.
     */
    public MockModel(List<String> responses) {
        this.responsesToReturn = new ArrayList<>();
        for (String response : responses) {
            this.responsesToReturn.add(createTextResponse(response));
        }
    }

    /**
     * Create a mock model with custom response generator.
     */
    public MockModel(Function<List<Msg>, List<ChatResponse>> responseGenerator) {
        this.responsesToReturn = new ArrayList<>();
        this.responseGenerator = responseGenerator;
    }

    /**
     * Create a mock model that returns thinking followed by a response.
     */
    public static MockModel withThinking(String thinking, String finalResponse) {
        MockModel model = new MockModel("");
        model.responsesToReturn.clear();
        model.responsesToReturn.add(createThinkingResponse(thinking));
        model.responsesToReturn.add(createTextResponse(finalResponse));
        return model;
    }

    /**
     * Create a mock model that calls a tool.
     */
    public static MockModel withToolCall(
            String toolName, String toolCallId, java.util.Map<String, Object> arguments) {
        MockModel model = new MockModel("");
        model.responsesToReturn.clear();
        model.responsesToReturn.add(createToolCallResponse(toolName, toolCallId, arguments));
        return model;
    }

    /**
     * Create a mock model that does thinking, tool call, then final response.
     */
    public static MockModel withFullFlow(String thinking, String toolName, String finalResponse) {
        MockModel model = new MockModel("");
        model.responsesToReturn.clear();
        model.responsesToReturn.add(createThinkingResponse(thinking));
        model.responsesToReturn.add(
                createToolCallResponse(toolName, "tool_call_1", new HashMap<>()));
        model.responsesToReturn.add(createTextResponse(finalResponse));
        return model;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        // Record the call
        this.callCount++;
        this.lastMessages = messages;
        this.lastTools = tools;
        this.lastOptions = options;

        // Throw error if configured
        if (shouldThrowError) {
            return Flux.error(new RuntimeException(errorMessage));
        }

        // Use custom generator if provided
        if (responseGenerator != null) {
            List<ChatResponse> responses = responseGenerator.apply(messages);
            return Flux.fromIterable(responses);
        }

        // Return configured responses
        if (responsesToReturn.isEmpty()) {
            return Flux.just(createTextResponse(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE));
        }

        return Flux.fromIterable(responsesToReturn);
    }

    /**
     * Configure the model to throw an error.
     */
    public MockModel withError(String errorMessage) {
        this.shouldThrowError = true;
        this.errorMessage = errorMessage;
        return this;
    }

    /**
     * Get the number of times the model was called.
     */
    public int getCallCount() {
        return callCount;
    }

    /**
     * Get the last messages sent to the model.
     */
    public List<Msg> getLastMessages() {
        return lastMessages;
    }

    /**
     * Get the last tools sent to the model.
     */
    public List<ToolSchema> getLastTools() {
        return lastTools;
    }

    /**
     * Get the last options sent to the model.
     */
    public GenerateOptions getLastOptions() {
        return lastOptions;
    }

    /**
     * Reset the mock state.
     */
    public void reset() {
        this.callCount = 0;
        this.lastMessages = null;
        this.lastTools = null;
        this.lastOptions = null;
        this.shouldThrowError = false;
    }

    @Override
    public String getModelName() {
        return "mock-model";
    }

    // Helper methods to create responses

    private static ChatResponse createTextResponse(String text) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID().toString())
                .content(java.util.List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    private static ChatResponse createThinkingResponse(String thinking) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID().toString())
                .content(java.util.List.of(ThinkingBlock.builder().thinking(thinking).build()))
                .usage(new ChatUsage(5, 10, 15))
                .build();
    }

    private static ChatResponse createToolCallResponse(
            String toolName, String toolCallId, java.util.Map<String, Object> arguments) {
        return ChatResponse.builder()
                .id("msg_" + UUID.randomUUID().toString())
                .content(
                        java.util.List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(
                                                toolCallId != null
                                                        ? toolCallId
                                                        : UUID.randomUUID().toString())
                                        .input(arguments != null ? arguments : new HashMap<>())
                                        .build()))
                .usage(new ChatUsage(8, 15, 23))
                .build();
    }
}
