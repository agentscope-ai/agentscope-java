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
package io.agentscope.core.tool;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified validator for tool-related operations.
 *
 * <p>This class provides validation capabilities for:
 * <ul>
 *   <li>Schema validation - Validates tool input parameters against JSON Schema using networknt-schema</li>
 *   <li>HITL validation - Validates ToolResult messages match pending ToolUse blocks</li>
 * </ul>
 */
public final class ToolValidator {

    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private ToolValidator() {
        // Utility class
    }

    // ==================== Schema Validation ====================

    /**
     * Validate tool input parameters against a JSON Schema using networknt-schema.
     *
     * <p>This method performs comprehensive JSON Schema validation including:
     * <ul>
     *   <li>Required fields check</li>
     *   <li>Type validation (string, number, integer, boolean, array, object)</li>
     *   <li>Enum values validation</li>
     *   <li>Minimum/maximum constraints for numbers</li>
     *   <li>MinLength/maxLength constraints for strings</li>
     *   <li>Pattern matching for strings</li>
     *   <li>Nested object and array validation</li>
     * </ul>
     *
     * @param input The input parameters to validate
     * @param schema The JSON Schema to validate against (from tool.getParameters())
     * @return null if validation passes, or an error message describing the validation failures
     */
    public static String validateInput(String input, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return null; // No schema, validation passes
        }

        try {
            // Convert schema to JSON string
            String schemaJson = JsonUtils.getJsonCodec().toJson(schema);

            // Create Schema from the schema string
            Schema jsonSchema = SCHEMA_REGISTRY.getSchema(schemaJson);

            // Validate
            List<Error> errors = jsonSchema.validate(input, InputFormat.JSON);

            if (errors.isEmpty()) {
                return null; // Validation passed
            }

            // Format error messages
            return errors.stream().map(Error::getMessage).collect(Collectors.joining("; "));

        } catch (Exception e) {
            return "Schema validation error: " + e.getMessage();
        }
    }

    // ==================== HITL Validation ====================

    /**
     * Validate that ToolResult messages match pending ToolUse blocks.
     *
     * <p>This validation is used in two scenarios:
     * <ul>
     *   <li>HITL (Human-in-the-Loop) resumption via {@code agent.call()}</li>
     *   <li>Hook-based flow control via {@code PostReasoningEvent.gotoReasoning()}</li>
     * </ul>
     *
     * <p>Validation rules:
     * <ul>
     *   <li>If assistantMsg is null or has no ToolUse blocks: validation passes</li>
     *   <li>If assistantMsg has ToolUse blocks but inputMsgs is null/empty: throws exception</li>
     *   <li>If inputMsgs is provided: checks that all pending ToolUse IDs have matching
     *       ToolResult IDs</li>
     * </ul>
     *
     * @param assistantMsg The assistant message that may contain pending ToolUse blocks
     * @param inputMsgs The input messages that should contain ToolResult blocks
     * @throws IllegalStateException if validation fails
     */
    public static void validateToolResultMatch(Msg assistantMsg, List<Msg> inputMsgs) {
        if (assistantMsg == null) {
            return;
        }

        List<ToolUseBlock> pendingToolUses = assistantMsg.getContentBlocks(ToolUseBlock.class);
        if (pendingToolUses.isEmpty()) {
            return; // No pending ToolUse, validation passes
        }

        // Has pending ToolUse
        if (inputMsgs == null || inputMsgs.isEmpty()) {
            // No input messages provided, throw error
            List<String> toolNames = pendingToolUses.stream().map(ToolUseBlock::getName).toList();
            throw new IllegalStateException(
                    "Cannot proceed without ToolResult when there are pending ToolUse. "
                            + "Pending tools: "
                            + toolNames);
        }

        // Has input messages, validate ToolResult matches
        Set<String> providedIds =
                inputMsgs.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        Set<String> requiredIds =
                pendingToolUses.stream().map(ToolUseBlock::getId).collect(Collectors.toSet());

        if (!providedIds.containsAll(requiredIds)) {
            Set<String> missing = new HashSet<>(requiredIds);
            missing.removeAll(providedIds);
            throw new IllegalStateException(
                    "Missing ToolResult for pending ToolUse. Missing IDs: " + missing);
        }
    }
}
