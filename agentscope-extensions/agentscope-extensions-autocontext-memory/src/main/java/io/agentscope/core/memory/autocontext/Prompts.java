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
package io.agentscope.core.memory.autocontext;

/**
 * Prompt templates for AutoContextMemory compression strategies.
 *
 * <p>Prompts are organized by compression strategy in progressive order (from lightweight to
 * heavyweight):
 * <ol>
 *   <li>Strategy 1: Tool invocation compression</li>
 *   <li>Strategy 2-3: Large message offloading</li>
 *   <li>Strategy 4: Previous round conversation summary</li>
 *   <li>Strategy 5: Current round large message summary</li>
 *   <li>Strategy 6: Current round message compression</li>
 * </ol>
 */
public class Prompts {

    // ============================================================================
    // Common: Compression Message List Scope Marker
    // ============================================================================

    /**
     * Generic prompt end marker for compression operations.
     *
     * <p>This marker is used to indicate the scope of messages that need to be compressed. It
     * serves as a boundary marker, indicating that all messages above this marker are the target
     * for compression.
     */
    public static final String COMPRESSION_MESSAGE_LIST_END =
            "Above is the message list that needs to be compressed.";

    // ============================================================================
    // Strategy 1: Previous Round Tool Invocation Compression
    // ============================================================================

    /** Prompt for compressing previous round tool invocations independently. */
    public static final String PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT =
            "You are an expert content compression specialist. Your task is to intelligently"
                + " compress and summarize the following tool invocation history:\n"
                + "    Summarize the tool responses while preserving key invocation details,"
                + " including the tool name, its purpose, and its output.\n"
                + "    For repeated calls to the same tool, consolidate the different parameters"
                + " and results, highlighting essential variations and outcomes.\n"
                + "    Special attention for write/change operations: If tool invocations involve"
                + " write or modification operations (such as file writing, data updates, state"
                + " changes, etc.), pay extra attention to preserve detailed information about what"
                + " was written, modified, or changed, including file paths, content summaries, and"
                + " modification results.\n"
                + "    Special handling for plan-related tools (create_plan, revise_current_plan,"
                + " update_subtask_state, finish_subtask, view_subtasks, finish_plan,"
                + " view_historical_plans, recover_historical_plan): Use minimal compression - only"
                + " keep a brief description indicating that plan-related tool calls were made,"
                + " without preserving detailed parameters, results, or intermediate states.";

    /** Format for compressed previous round tool invocation history. */
    public static final String PREVIOUS_ROUND_COMPRESSED_TOOL_INVOCATION_FORMAT =
            "<compressed_history>%s</compressed_history>\n"
                    + "<hint> You can use this information as historical context for future"
                    + " reference in carrying out your tasks\n";

    // ============================================================================
    // Strategy 2-3: Large Message Offloading
    // ============================================================================

    /** Generic offload hint for offloaded content. */
    public static final String OFFLOAD_HINT =
            "<hint> The original content is stored with"
                    + " working_context_offload_uuid: %s. If you need to retrieve"
                    + " the full content, please use the context_reload tool with"
                    + " this UUID.</hint>";

    // ============================================================================
    // Strategy 4: Previous Round Conversation Summary
    // ============================================================================

    /** Prompt for summarizing previous round conversations. */
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT =
            "You are an expert content compression specialist. Your task is to intelligently"
                + " summarize the following conversation history from a previous round. The content"
                + " includes a user question, tool invocations and their results, and the"
                + " assistant's final response for that round.\n"
                + "\n"
                + "Please provide a concise summary that:\n"
                + "    - Preserves important decisions, conclusions, and key information\n"
                + "    - Maintains context that would be needed for future interactions\n"
                + "    - Consolidates repeated or similar information\n"
                + "    - Highlights any important outcomes or results\n"
                + "    - Special attention for write/change operations: If tool invocations involve"
                + " write or modification operations (such as file writing, data updates, state"
                + " changes, etc.), pay extra attention to preserve detailed information about what"
                + " was written, modified, or changed, including file paths, content summaries, and"
                + " modification results\n"
                + "    - Provide a clear summary of the assistant's final response in that round,"
                + " highlighting the key points, conclusions, or actions taken";

    /** Format for previous round conversation summary. */
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY_FORMAT =
            "<conversation_summary>%s</conversation_summary>\n"
                    + "<hint> This is a summary of previous conversation rounds. You can use this"
                    + " information as historical context for future reference.\n";

    // ============================================================================
    // Strategy 5: Current Round Large Message Summary
    // ============================================================================

    /** Prompt for summarizing current round large messages. */
    public static final String CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT =
            "You are an expert content compression specialist. Your task is to intelligently"
                + " summarize the following message content. This message exceeds the size"
                + " threshold and needs to be compressed while preserving all critical"
                + " information.\n"
                + "\n"
                + "IMPORTANT: This is content from the CURRENT ROUND. Please be EXTRA CAREFUL and"
                + " CONSERVATIVE when compressing. Preserve as much content as possible according"
                + " to the requirements below, as this information is actively being used in the"
                + " current conversation.\n"
                + "\n"
                + "Please provide a concise summary that:\n"
                + "    - Preserves all critical information and key details\n"
                + "    - Maintains important context that would be needed for future reference\n"
                + "    - Highlights any important outcomes, results, or status information\n"
                + "    - Retains tool call information if present (tool names, IDs, key"
                + " parameters)";

    // ============================================================================
    // Strategy 6: Current Round Message Compression
    // ============================================================================

    /** Prompt for compressing current round messages (main instruction, without character count requirement). */
    public static final String CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT =
            "You are an expert content compression specialist. Your task is to compress and"
                + " summarize the following current round messages (tool calls and results).\n"
                + "\n"
                + "IMPORTANT: This is content from the CURRENT ROUND. Please be EXTRA CAREFUL and"
                + " CONSERVATIVE when compressing. Preserve as much content as possible, as this"
                + " information is actively being used in the current conversation.\n"
                + "\n"
                + "Compression principles:\n"
                + "    - Preserve all critical information, key details, and important context\n"
                + "    - Retain tool names, IDs, and important parameters\n"
                + "    - Keep key results, outcomes, and status information\n"
                + "    - Maintain logical flow and relationships between tool calls\n"
                + "    - Only remove redundant or less critical information\n"
                + "\n"
                + "Special handling for plan-related tools:\n"
                + "    - Plan-related tools (create_plan, revise_current_plan,"
                + " update_subtask_state, finish_subtask, view_subtasks, finish_plan,"
                + " view_historical_plans, recover_historical_plan): Plan-related information is"
                + " stored in the current PlanNotebook, so these tool calls can be more"
                + " aggressively compressed with concise task-focused summaries\n"
                + "\n"
                + "Compression techniques:\n"
                + "    1. Count your output characters as you write and adjust detail level to meet"
                + " the character limit as much as possible\n"
                + "    2. Consolidate similar or repeated information\n"
                + "    3. Use concise language while preserving meaning\n"
                + "    4. Merge related tool calls and results when appropriate\n"
                + "    5. Remove verbose descriptions but keep essential facts\n"
                + "    6. Focus on actionable information and outcomes\n"
                + "    7. Special attention for write/change operations: If tool invocations"
                + " involve write or modification operations (such as file writing, data updates,"
                + " state changes, etc.), pay extra attention to preserve detailed information"
                + " about what was written, modified, or changed, including file paths, content"
                + " summaries, and modification results";

    /**
     * Character count requirement template for current round message compression.
     * This should be placed after the message list end marker.
     * Format parameters: %d (originalCharCount), %d (targetCharCount), %.0f (compressionRatioPercent), %.0f (compressionRatioPercent).
     */
    public static final String CURRENT_ROUND_MESSAGE_COMPRESS_CHAR_REQUIREMENT =
            "\n"
                + "COMPRESSION REQUIREMENT:\n"
                + "The original content contains approximately %d characters. You MUST compress it"
                + " to approximately %d characters (%.0f%% of original). This is a STRICT"
                + " requirement.\n"
                + "\n"
                + "Please ensure your output meets this character limit while following the"
                + " compression principles above.";
}
