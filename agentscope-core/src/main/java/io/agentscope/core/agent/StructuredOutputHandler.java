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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.serialization.DefaultNodeReader;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Handles structured output tool registration and result extraction for ReActAgent.
 *
 * <p>This class encapsulates the tool registration and result extraction functionality:
 *
 * <ul>
 *   <li>Temporary tool registration ({@code generate_response})
 *   <li>Response validation against JSON schema
 *   <li>Result extraction from tool execution output
 * </ul>
 *
 * <p><b>Lifecycle:</b>
 *
 * <pre>
 * 1. create() - Create handler instance
 * 2. prepare() - Register tool
 * 3. [Agent execution with StructuredOutputHook managing the loop]
 * 4. extractResult() - Extract final result from hook's output
 * 5. cleanup() - Unregister tool
 * </pre>
 *
 * @hidden
 */
public class StructuredOutputHandler {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHandler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** The tool name for structured output generation. */
    public static final String TOOL_NAME = "generate_response";

    private final Class<?> targetClass;
    private final JsonNode schemaDesc;
    private final Toolkit toolkit;
    private final String agentName;

    /**
     * Create a structured output handler.
     *
     * @param targetClass The target class for structured output (may be null if schemaDesc is
     *     provided)
     * @param schemaDesc The json schema for structured output (may be null if targetClass is
     *     provided)
     * @param toolkit The toolkit for tool registration
     * @param agentName The agent name for message creation
     */
    public StructuredOutputHandler(
            Class<?> targetClass, JsonNode schemaDesc, Toolkit toolkit, String agentName) {
        this.targetClass = targetClass;
        this.schemaDesc = schemaDesc;
        this.toolkit = toolkit;
        this.agentName = agentName;
    }

    // ==================== Lifecycle Methods ====================

    /**
     * Prepare for structured output execution. Registers temporary tool for structured output
     * generation.
     */
    public void prepare() {
        if (Objects.isNull(targetClass) && Objects.isNull(schemaDesc)) {
            throw new IllegalStateException(
                    "Cannot prepare, because targetClass and schemaDesc both not exists");
        }
        if (Objects.nonNull(targetClass) && Objects.nonNull(schemaDesc)) {
            throw new IllegalStateException(
                    "Cannot prepare, because targetClass and schemaDesc both exists");
        }
        Map<String, Object> jsonSchema =
                Objects.nonNull(targetClass)
                        ? JsonSchemaUtils.generateSchemaFromClass(targetClass)
                        : JsonSchemaUtils.generateSchemaFromJsonNode(schemaDesc);
        AgentTool temporaryTool = createStructuredOutputTool(jsonSchema);
        toolkit.registerAgentTool(temporaryTool);

        if (log.isDebugEnabled()) {
            String schema = "";
            try {
                schema = OBJECT_MAPPER.writeValueAsString(temporaryTool.getParameters());
            } catch (JsonProcessingException e) {
                // ignore
            }
            log.debug("Structured output handler prepared, schema: {}", schema);
        }
    }

    /**
     * Cleanup after structured output execution. Unregisters temporary tool and resets state.
     */
    public void cleanup() {
        toolkit.removeTool(TOOL_NAME);
        log.debug("Structured output cleanup completed");
    }

    // ==================== Result Extraction ====================

    /**
     * Extract the final result from the hook's output message.
     *
     * @param hookResultMsg The message from StructuredOutputHook.getResultMsg()
     * @return The final message with structured data in metadata
     */
    public Msg extractResult(Msg hookResultMsg) {
        if (hookResultMsg == null) {
            return null;
        }

        // Check if the message has ToolResultBlock with response_msg
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

        // If no tool result found, return the message as-is
        return hookResultMsg;
    }

    // ==================== Private Helper Methods ====================

    private AgentTool createStructuredOutputTool(Map<String, Object> schema) {
        return new AgentTool() {
            @Override
            public String getName() {
                return TOOL_NAME;
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

                            if ((targetClass != null || schemaDesc != null)
                                    && responseData != null) {
                                try {
                                    if (Objects.nonNull(targetClass)) {
                                        OBJECT_MAPPER.convertValue(responseData, targetClass);
                                    } else {
                                        SchemaRegistry schemaRegistry =
                                                SchemaRegistry.withDialect(
                                                        Dialects.getDraft202012(),
                                                        builder ->
                                                                builder.nodeReader(
                                                                        DefaultNodeReader.Builder
                                                                                ::locationAware));
                                        Schema validationSchema =
                                                schemaRegistry.getSchema(schemaDesc);
                                        List<Error> errors =
                                                validationSchema.validate(
                                                        OBJECT_MAPPER.writeValueAsString(
                                                                responseData),
                                                        InputFormat.JSON,
                                                        executionContext ->
                                                                executionContext.executionConfig(
                                                                        executionConfig ->
                                                                                executionConfig
                                                                                        .formatAssertionsEnabled(
                                                                                                true)));
                                        if (Objects.nonNull(errors) && !errors.isEmpty()) {
                                            StringBuilder err = new StringBuilder();
                                            errors.forEach(e -> err.append(e.getMessage()));
                                            throw new RuntimeException(err.toString());
                                        }
                                    }
                                } catch (Exception e) {
                                    String simplifiedError = simplifyValidationError(e);
                                    String errorMsg =
                                            String.format(
                                                    "Schema validation failed: %s\n\n"
                                                        + "Please review the expected structure and"
                                                        + " call 'generate_response' again with a"
                                                        + " correctly formatted response object.",
                                                    simplifiedError);
                                    log.error(errorMsg, e);

                                    Map<String, Object> errorMetadata = new HashMap<>();
                                    errorMetadata.put("success", false);
                                    errorMetadata.put("validation_error", simplifiedError);

                                    return ToolResultBlock.of(
                                            List.of(TextBlock.builder().text(errorMsg).build()),
                                            errorMetadata);
                                }
                            } else {
                                log.error(
                                        "Structured output generate failed, target class or schema"
                                                + " is null.");
                            }

                            String contentText = "";
                            if (responseData != null) {
                                try {
                                    contentText = OBJECT_MAPPER.writeValueAsString(responseData);
                                } catch (Exception e) {
                                    contentText = responseData.toString();
                                }
                            }
                            log.debug(
                                    "Structured output generate success, output: {}", contentText);

                            Msg responseMsg =
                                    Msg.builder()
                                            .name(agentName)
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

    private String simplifyValidationError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "Unable to parse response structure";
        }

        int newlineIndex = message.indexOf('\n');
        if (newlineIndex > 0) {
            message = message.substring(0, newlineIndex);
        }

        if (message.length() > 200) {
            message = message.substring(0, 197) + "...";
        }

        return message;
    }

    private Msg extractResponseData(Msg responseMsg) {
        if (responseMsg.getMetadata() != null
                && responseMsg.getMetadata().containsKey("response")) {
            Object responseData = responseMsg.getMetadata().get("response");
            return Msg.builder()
                    .name(responseMsg.getName())
                    .role(responseMsg.getRole())
                    .content(responseMsg.getContent())
                    .metadata(
                            responseData instanceof Map
                                    ? (Map<String, Object>) responseData
                                    : Map.of("data", responseData))
                    .build();
        }
        return responseMsg;
    }
}
