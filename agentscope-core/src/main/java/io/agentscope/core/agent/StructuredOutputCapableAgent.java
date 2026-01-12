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
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for agents that support structured output generation.
 *
 * <p>This class provides the infrastructure for generating structured output using the
 * {@code generate_response} tool pattern combined with StructuredOutputHook for flow control.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Automatic tool registration for structured output</li>
 *   <li>Schema validation before tool execution</li>
 *   <li>Memory compression after structured output completion</li>
 *   <li>Configurable reminder mode (TOOL_CHOICE or PROMPT)</li>
 * </ul>
 *
 * <p><b>Subclass Requirements:</b>
 * <ul>
 *   <li>Provide Toolkit via constructor</li>
 *   <li>Implement {@link #getMemory()} for memory access</li>
 *   <li>Implement {@link #buildGenerateOptions()} for model options</li>
 * </ul>
 */
public abstract class StructuredOutputCapableAgent extends AgentBase {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputCapableAgent.class);

    /** The tool name for structured output generation. */
    public static final String STRUCTURED_OUTPUT_TOOL_NAME = "generate_response";

    protected final Toolkit toolkit;
    protected final StructuredOutputReminder structuredOutputReminder;

    /**
     * Constructor with default reminder mode (TOOL_CHOICE).
     */
    protected StructuredOutputCapableAgent(
            String name,
            String description,
            boolean checkRunning,
            List<Hook> hooks,
            Toolkit toolkit) {
        this(name, description, checkRunning, hooks, toolkit, StructuredOutputReminder.TOOL_CHOICE);
    }

    /**
     * Constructor with custom reminder mode.
     */
    protected StructuredOutputCapableAgent(
            String name,
            String description,
            boolean checkRunning,
            List<Hook> hooks,
            Toolkit toolkit,
            StructuredOutputReminder structuredOutputReminder) {
        super(name, description, checkRunning, hooks);
        this.toolkit = toolkit != null ? toolkit : new Toolkit();
        this.structuredOutputReminder =
                structuredOutputReminder != null
                        ? structuredOutputReminder
                        : StructuredOutputReminder.TOOL_CHOICE;
    }

    /**
     * Get the toolkit for tool operations.
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * Get the memory for structured output hook.
     * Subclasses must implement this.
     */
    public abstract Memory getMemory();

    /**
     * Build generate options for model calls.
     * Subclasses must implement this.
     */
    protected abstract GenerateOptions buildGenerateOptions();

    // ==================== Structured Output Implementation ====================

    @Override
    protected final Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        return executeWithStructuredOutput(msgs, structuredOutputClass, null);
    }

    @Override
    protected final Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        return executeWithStructuredOutput(msgs, null, outputSchema);
    }

    /**
     * Execute with structured output using StructuredOutputHook.
     */
    private Mono<Msg> executeWithStructuredOutput(
            List<Msg> msgs, Class<?> targetClass, JsonNode schemaDesc) {

        // Validate parameters
        if (targetClass == null && schemaDesc == null) {
            return Mono.error(
                    new IllegalArgumentException(
                            "Either targetClass or schemaDesc must be provided"));
        }
        if (targetClass != null && schemaDesc != null) {
            return Mono.error(
                    new IllegalArgumentException("Cannot provide both targetClass and schemaDesc"));
        }

        return Mono.defer(
                () -> {
                    // Create and register temporary tool
                    Map<String, Object> jsonSchema =
                            targetClass != null
                                    ? JsonSchemaUtils.generateSchemaFromClass(targetClass)
                                    : JsonSchemaUtils.generateSchemaFromJsonNode(schemaDesc);
                    AgentTool structuredOutputTool =
                            createStructuredOutputTool(jsonSchema, targetClass, schemaDesc);
                    toolkit.registerAgentTool(structuredOutputTool);

                    // Create hook for flow control
                    StructuredOutputHook hook =
                            new StructuredOutputHook(
                                    structuredOutputReminder, buildGenerateOptions(), getMemory());

                    addHook(hook);

                    return doCall(msgs)
                            .flatMap(
                                    result -> {
                                        // Extract result from hook's output
                                        Msg hookResult = hook.getResultMsg();
                                        if (hookResult != null) {
                                            return Mono.just(extractStructuredResult(hookResult));
                                        }
                                        return Mono.just(result);
                                    })
                            .doFinally(
                                    signal -> {
                                        // Cleanup: remove hook and unregister tool
                                        removeHook(hook);
                                        toolkit.removeTool(STRUCTURED_OUTPUT_TOOL_NAME);
                                    });
                });
    }

    /**
     * Create the structured output tool with validation.
     */
    private AgentTool createStructuredOutputTool(
            Map<String, Object> schema, Class<?> targetClass, JsonNode schemaDesc) {
        return new AgentTool() {
            @Override
            public String getName() {
                return STRUCTURED_OUTPUT_TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return "Generate the final structured response. Call this function when"
                        + " you have all the information needed to provide a complete answer.";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> params = new HashMap<>();
                params.put("type", "object");
                params.put("properties", Map.of("response", schema));
                params.put("required", List.of("response"));
                return params;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(
                        () -> {
                            Object responseData = param.getInput().get("response");

                            // The tool simply stores the raw data
                            // Validation is done by ToolExecutor before calling this
                            String contentText = "";
                            if (responseData != null) {
                                try {
                                    contentText = JsonUtils.getJsonCodec().toJson(responseData);
                                } catch (Exception e) {
                                    contentText = responseData.toString();
                                }
                            }

                            log.debug("Structured output generated: {}", contentText);

                            // Create response message
                            Msg responseMsg =
                                    Msg.builder()
                                            .name(getName())
                                            .role(MsgRole.ASSISTANT)
                                            .content(TextBlock.builder().text(contentText).build())
                                            .metadata(
                                                    responseData != null
                                                            ? Map.of("response", responseData)
                                                            : Map.of())
                                            .build();

                            Map<String, Object> toolMetadata = new HashMap<>();
                            toolMetadata.put("success", true);
                            toolMetadata.put("response_msg", responseMsg);

                            return ToolResultBlock.of(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("Successfully generated response.")
                                                    .build()),
                                    toolMetadata);
                        });
            }
        };
    }

    /**
     * Extract structured result from tool result message.
     */
    private Msg extractStructuredResult(Msg hookResultMsg) {
        if (hookResultMsg == null) {
            return null;
        }

        List<ToolResultBlock> toolResults = hookResultMsg.getContentBlocks(ToolResultBlock.class);
        for (ToolResultBlock result : toolResults) {
            if (result.getMetadata() != null
                    && Boolean.TRUE.equals(result.getMetadata().get("success"))
                    && result.getMetadata().containsKey("response_msg")) {
                Object responseMsgObj = result.getMetadata().get("response_msg");
                if (responseMsgObj instanceof Msg responseMsg) {
                    return extractResponseData(responseMsg);
                }
            }
        }

        return hookResultMsg;
    }

    @SuppressWarnings("unchecked")
    private Msg extractResponseData(Msg responseMsg) {
        if (responseMsg.getMetadata() != null
                && responseMsg.getMetadata().containsKey("response")) {
            Object responseData = responseMsg.getMetadata().get("response");
            // Store structured output under dedicated key to avoid conflicts with other metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, responseData);
            return Msg.builder()
                    .name(responseMsg.getName())
                    .role(responseMsg.getRole())
                    .content(responseMsg.getContent())
                    .metadata(metadata)
                    .build();
        }
        return responseMsg;
    }
}
