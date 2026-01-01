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
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.serialization.DefaultNodeReader;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.MessageUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Handles structured output generation logic for ReActAgent.
 *
 * <p>This class encapsulates all structured output related functionality including:
 * <ul>
 *   <li>Temporary tool registration and cleanup
 *   <li>Memory checkpoint and rollback
 *   <li>Reminder message injection
 *   <li>Response validation and extraction
 * </ul>
 *
 * <p><b>Lifecycle:</b>
 * <pre>
 * 1. create() - Create handler instance
 * 2. prepare() - Register tool, mark memory checkpoint
 * 3. [Loop execution with needsRetry/isCompleted checks]
 * 4. extractFinalResult() - Extract and cleanup
 * 5. cleanup() - Unregister tool
 * </pre>
 * @hidden
 */
public class StructuredOutputHandler {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHandler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Class<?> targetClass;
    private final JsonNode schemaDesc;
    private final Toolkit toolkit;
    private final Memory memory;
    private final String agentName;
    private final StructuredOutputReminder reminder;

    // State management
    private boolean needsReminder = false;
    private boolean needsForcedToolChoice = false;

    /**
     * Create a structured output handler.
     *
     * @param targetClass The target class for structured output
     * @param schemaDesc  The json schema for structured output
     * @param toolkit The toolkit for tool registration
     * @param memory The memory for checkpoint management
     * @param agentName The agent name for message creation
     * @param reminder The reminder mode (TOOL_CHOICE or PROMPT)
     */
    public StructuredOutputHandler(
            Class<?> targetClass,
            JsonNode schemaDesc,
            Toolkit toolkit,
            Memory memory,
            String agentName,
            StructuredOutputReminder reminder) {
        this.targetClass = targetClass;
        this.schemaDesc = schemaDesc;
        this.toolkit = toolkit;
        this.memory = memory;
        this.agentName = agentName;
        this.reminder = reminder;
    }

    // ==================== Lifecycle Methods ====================

    /**
     * Prepare for structured output execution.
     * Registers temporary tool for structured output generation.
     */
    public void prepare() {
        if (Objects.isNull(targetClass) && Objects.isNull(schemaDesc)) {
            throw new IllegalStateException(
                    "Can not prepare,because targetClass and schemaDesc both not exists");
        }
        if (Objects.nonNull(targetClass) && Objects.nonNull(schemaDesc)) {
            throw new IllegalStateException(
                    "Can not prepare,because targetClass and schemaDesc both exists");
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
     * Cleanup after structured output execution.
     * Unregisters temporary tool and resets state.
     */
    public void cleanup() {
        toolkit.removeTool("generate_response");
        needsReminder = false;
        needsForcedToolChoice = false;
        log.debug("Structured output cleanup completed");
    }

    // ==================== Loop Interaction Methods ====================

    /**
     * Create GenerateOptions with forced tool choice for structured output.
     * Only applies tool_choice when reminder mode is TOOL_CHOICE and the model
     * has failed to call generate_response in a previous iteration.
     *
     * @param baseOptions Base generation options to merge with (may be null)
     * @return New GenerateOptions with toolChoice set to force generate_response
     *     (if TOOL_CHOICE mode and retry needed), or original options otherwise
     */
    public GenerateOptions createOptionsWithForcedTool(GenerateOptions baseOptions) {
        if (reminder != StructuredOutputReminder.TOOL_CHOICE || !needsForcedToolChoice) {
            return baseOptions;
        }

        return GenerateOptions.mergeOptions(
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Specific("generate_response"))
                        .build(),
                baseOptions);
    }

    /**
     * Check if structured output generation is completed.
     *
     * @return true if completed successfully
     */
    public boolean isCompleted() {
        return checkStructuredOutputResponse() != null;
    }

    /**
     * Extract and cleanup final result.
     *
     * @return Final message with structured data in metadata
     */
    public Msg extractFinalResult() {
        Msg rawResult = checkStructuredOutputResponse();
        if (rawResult == null) {
            throw new IllegalStateException(
                    "Structured output not found when extractFinalResult called");
        }

        Msg cleanedMsg = extractResponseData(rawResult);
        cleanupStructuredOutputHistory(cleanedMsg);

        return cleanedMsg;
    }

    /**
     * Check if a reminder message should be injected (PROMPT mode only).
     *
     * @return true if reminder is needed
     */
    public boolean shouldInjectReminder() {
        if (reminder == StructuredOutputReminder.PROMPT && needsReminder) {
            needsReminder = false;
            return true;
        }
        return false;
    }

    /**
     * Create reminder message for the model (PROMPT mode only).
     *
     * @return Reminder message
     */
    public Msg createReminderMessage() {
        String reminderText =
                "To complete this request, call the 'generate_response' function "
                        + "with your answer formatted according to the specified schema.";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MessageMetadataKeys.BYPASS_MULTIAGENT_HISTORY_MERGE, true);
        metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER, true);

        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(reminderText).build())
                .metadata(metadata)
                .build();
    }

    /**
     * Check if the loop needs to retry (model didn't call the tool).
     * In PROMPT mode, sets needsReminder flag for reminder injection.
     * In TOOL_CHOICE mode, sets needsForcedToolChoice flag for forced tool choice.
     *
     * @return true if should retry
     */
    public boolean needsRetry() {
        List<ToolUseBlock> recentToolCalls = extractRecentToolCalls();

        if (recentToolCalls.isEmpty()) {
            if (reminder == StructuredOutputReminder.PROMPT) {
                log.debug("Model didn't call generate_response, will add reminder");
                needsReminder = true;
            } else if (reminder == StructuredOutputReminder.TOOL_CHOICE) {
                log.debug("Model didn't call generate_response, will force tool choice");
                needsForcedToolChoice = true;
            }
            return true;
        }

        return false;
    }

    // ==================== Private Helper Methods ====================

    private AgentTool createStructuredOutputTool(Map<String, Object> schema) {
        return new AgentTool() {
            @Override
            public String getName() {
                return "generate_response";
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
                                        com.networknt.schema.Schema schema =
                                                schemaRegistry.getSchema(schemaDesc);
                                        List<Error> errors =
                                                schema.validate(
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

    /**
     * Extract tool calls from the most recent assistant message.
     *
     * <p>Delegates to {@link MessageUtils#extractRecentToolCalls(List, String)} for the actual
     * extraction logic. Uses the agentName parameter to identify the relevant messages, which may
     * differ from the outer agent's name in multi-agent scenarios.
     *
     * @return List of tool use blocks from the last assistant message, or empty list if none found
     */
    private List<ToolUseBlock> extractRecentToolCalls() {
        return MessageUtils.extractRecentToolCalls(memory.getMessages(), agentName);
    }

    private Msg checkStructuredOutputResponse() {
        List<Msg> msgs = memory.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg.getRole() == MsgRole.TOOL) {
                List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);
                for (ToolResultBlock result : toolResults) {
                    if (result.getMetadata() != null
                            && Boolean.TRUE.equals(result.getMetadata().get("success"))
                            && result.getMetadata().containsKey("response_msg")) {
                        Object responseMsgObj = result.getMetadata().get("response_msg");
                        if (responseMsgObj instanceof Msg responseMsg) {
                            return responseMsg;
                        }
                    }
                }
                break;
            }
        }
        return null;
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

    private void cleanupStructuredOutputHistory(Msg finalResponseMsg) {
        List<Msg> currentMessages = memory.getMessages();
        int currentSize = currentMessages.size();

        if (currentSize < 2) {
            log.warn("Not enough messages to cleanup, adding final response only");
            memory.addMessage(finalResponseMsg);
            return;
        }

        // Find and remove the last generate_response tool call and its result
        // Expected structure at the end of memory:
        // - ASSISTANT message with generate_response ToolUseBlock
        // - TOOL message with generate_response ToolResultBlock
        int toolMsgIndex = -1;
        int assistantMsgIndex = -1;

        // Find the last TOOL message containing generate_response result
        for (int i = currentSize - 1; i >= 0; i--) {
            Msg msg = currentMessages.get(i);
            if (msg.getRole() == MsgRole.TOOL) {
                List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);
                for (ToolResultBlock result : toolResults) {
                    if (result.getMetadata() != null
                            && result.getMetadata().containsKey("response_msg")) {
                        toolMsgIndex = i;
                        break;
                    }
                }
                if (toolMsgIndex >= 0) {
                    break;
                }
            }
        }

        // Find the corresponding ASSISTANT message with generate_response tool call
        if (toolMsgIndex > 0) {
            for (int i = toolMsgIndex - 1; i >= 0; i--) {
                Msg msg = currentMessages.get(i);
                if (msg.getRole() == MsgRole.ASSISTANT) {
                    List<ToolUseBlock> toolUses = msg.getContentBlocks(ToolUseBlock.class);
                    for (ToolUseBlock toolUse : toolUses) {
                        if ("generate_response".equals(toolUse.getName())) {
                            assistantMsgIndex = i;
                            break;
                        }
                    }
                    if (assistantMsgIndex >= 0) {
                        break;
                    }
                }
            }
        }

        if (toolMsgIndex < 0 || assistantMsgIndex < 0) {
            log.warn(
                    "Could not find generate_response messages to cleanup, adding final response"
                            + " only");
            memory.addMessage(finalResponseMsg);
            return;
        }

        log.debug(
                "Cleaning up generate_response messages: assistant at {}, tool at {}",
                assistantMsgIndex,
                toolMsgIndex);

        // Remove all messages from the first generate_response call to the end
        // This handles cases where the model retried multiple times, leaving multiple
        // intermediate ASSISTANT/TOOL message pairs in memory
        int messagesToDelete = currentSize - assistantMsgIndex;
        for (int i = 0; i < messagesToDelete; i++) {
            memory.deleteMessage(
                    assistantMsgIndex); // Always delete at same index after each removal
        }

        memory.addMessage(finalResponseMsg);

        log.debug(
                "Cleanup complete. Memory now has {} messages (was {}, deleted {})",
                memory.getMessages().size(),
                currentSize,
                messagesToDelete);
    }
}
