/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
    // Strategy 1: Tool Invocation Compression
    // ============================================================================

    /** Prompt start for compressing historical tool invocations. */
    public static final String TOOL_INVOCATION_COMPRESS_PROMPT_START =
            "Please intelligently compress and summarize the following tool invocation history";

    /** Prompt end for compressing historical tool invocations. */
    public static final String TOOL_INVOCATION_COMPRESS_PROMPT_END =
            "Above is a history of tool invocations. \n"
                + "Please intelligently compress and summarize the following tool invocation"
                + " history:\n"
                + "    Summarize the tool responses while preserving key invocation details,"
                + " including the tool name, its purpose, and its output.\n"
                + "    For repeated calls to the same tool, consolidate the different parameters"
                + " and results, highlighting essential variations and outcomes.\n"
                + "    Special handling for plan-related tools (create_plan, revise_current_plan,"
                + " update_subtask_state, finish_subtask, view_subtasks, finish_plan,"
                + " view_historical_plans, recover_historical_plan): Use minimal compression - only"
                + " keep a brief description indicating that plan-related tool calls were made,"
                + " without preserving detailed parameters, results, or intermediate states.";

    /** Format for compressed tool invocation history. */
    public static final String COMPRESSED_TOOL_INVOCATION_FORMAT =
            "<compressed_history>%s</compressed_history>\n"
                    + "<hint> You can use this information as historical context for future"
                    + " reference in carrying out your tasks\n";

    /** Offload hint for compressed tool invocation history. */
    public static final String COMPRESSED_TOOL_INVOCATION_OFFLOAD_HINT =
            "<hint> The original tools invocation is stored in the offload"
                    + " with working_context_offload_uuid: %s. if you need to retrieve it, please"
                    + " use the context offload tool to get it. \n";

    // ============================================================================
    // Strategy 2-3: Large Message Offloading
    // ============================================================================

    /** Format for offloaded large messages with preview and reload hint. */
    public static final String LARGE_MESSAGE_OFFLOAD_FORMAT =
            "%s\n"
                    + "<hint> This message content has been offloaded due to large"
                    + " size. The original content is stored with"
                    + " working_context_offload_uuid: %s. If you need to retrieve"
                    + " the full content, please use the context_reload tool with"
                    + " this UUID.</hint>";

    // ============================================================================
    // Strategy 4: Previous Round Conversation Summary
    // ============================================================================

    /** Prompt start for summarizing previous round conversations. */
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT_START =
            "Please intelligently summarize the following conversation history. Preserve key"
                    + " information, decisions, and context that would be important for future"
                    + " reference.";

    /** Prompt end for summarizing previous round conversations. */
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT_END =
            "Above is a conversation history. \n"
                    + "Please provide a concise summary that:\n"
                    + "    - Preserves important decisions, conclusions, and key information\n"
                    + "    - Maintains context that would be needed for future interactions\n"
                    + "    - Consolidates repeated or similar information\n"
                    + "    - Highlights any important outcomes or results";

    /** Format for previous round conversation summary. */
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY_FORMAT =
            "<conversation_summary>%s</conversation_summary>\n"
                    + "<hint> This is a summary of previous conversation rounds. You can use this"
                    + " information as historical context for future reference.\n";

    /** Offload hint for previous round conversation summary. */
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY_OFFLOAD_HINT =
            "<hint> The original conversation is stored in the offload "
                    + "with working_context_offload_uuid: %s. If you need to retrieve the full"
                    + " conversation, please use the context_reload tool with this UUID.</hint>";

    // ============================================================================
    // Strategy 5: Current Round Large Message Summary
    // ============================================================================

    /** Prompt start for summarizing current round large messages. */
    public static final String CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT_START =
            "Please intelligently summarize the following message content. This message exceeds"
                    + " the size threshold and needs to be compressed while preserving all critical"
                    + " information.";

    /** Prompt end for summarizing current round large messages. */
    public static final String CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT_END =
            "Above is a large message that needs to be summarized.\n"
                + "Please provide a concise summary that:\n"
                + "    - Preserves all critical information and key details\n"
                + "    - Maintains important context that would be needed for future reference\n"
                + "    - Highlights any important outcomes, results, or status information\n"
                + "    - Retains tool call information if present (tool names, IDs, key"
                + " parameters)";

    /** Format for compressed current round large message. */
    public static final String COMPRESSED_CURRENT_ROUND_LARGE_MESSAGE_FORMAT =
            "<compressed_large_message>%s</compressed_large_message>%s";

    // ============================================================================
    // Strategy 6: Current Round Message Compression
    // ============================================================================

    /** Prompt start for compressing current round messages. */
    public static final String CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT_START =
            "Please compress and summarize the following current round messages (tool calls and"
                    + " results).\n"
                    + "\n"
                    + "IMPORTANT COMPRESSION REQUIREMENT:\n"
                    + "The original content contains approximately %d characters. You MUST compress"
                    + " it to approximately %d characters (%.0f%% of original). This is a STRICT"
                    + " requirement - your output should be approximately %d characters.\n"
                    + "\n"
                    + "Compression guidelines:\n"
                    + "- For %.0f%% compression (low compression rate), you should:\n"
                    + "  * Keep most details and context\n"
                    + "  * Preserve tool names, IDs, and important parameters\n"
                    + "  * Retain key results, outcomes, and status information\n"
                    + "  * Maintain logical flow and relationships between tool calls\n"
                    + "  * Only remove redundant or less critical information\n"
                    + "\n"
                    + "Special handling for plan-related tools:\n"
                    + "- For plan-related tools (create_plan, revise_current_plan,"
                    + " update_subtask_state, finish_subtask, view_subtasks, finish_plan,"
                    + " view_historical_plans, recover_historical_plan):\n"
                    + "  * Use more concise summarization - focus on task-related information\n"
                    + "  * Keep brief descriptions of what plan operations were performed\n"
                    + "  * Retain key task information and outcomes, but reduce detailed"
                    + " parameters\n"
                    + "  * Prioritize information directly related to task execution\n"
                    + "\n"
                    + "To achieve the target character count (%d characters):\n"
                    + "1. Count your output characters as you write\n"
                    + "2. Consolidate similar or repeated information\n"
                    + "3. Use concise language while preserving meaning\n"
                    + "4. Merge related tool calls and results when appropriate\n"
                    + "5. Remove verbose descriptions but keep essential facts\n"
                    + "6. Focus on actionable information and outcomes\n"
                    + "7. Adjust detail level to meet the character limit";

    /** Prompt end for compressing current round messages. */
    public static final String CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT_END =
            "Above are the current round messages that need to be summarized.\n"
                + "\n"
                + "Please provide a summary that:\n"
                + "    - Preserves all critical information and key details\n"
                + "    - Maintains important context for future reference\n"
                + "    - Highlights important outcomes, results, and status information\n"
                + "    - Retains tool call information (tool names, IDs, key parameters)\n"
                + "    - For plan-related tools: focuses on task-related information with concise"
                + " descriptions\n"
                + "    - STRICTLY adheres to the target character count: approximately %d"
                + " characters (%.0f%% of original %d characters)\n"
                + "\n"
                + "CRITICAL: Your output MUST be approximately %d characters. Count your characters"
                + " carefully and adjust the level of detail to meet this requirement.";

    /** Format for compressed current round messages. */
    public static final String COMPRESSED_CURRENT_ROUND_MESSAGE_FORMAT =
            "<compressed_current_round>%s</compressed_current_round>%s";

    /** Offload hint for compressed current round messages. */
    public static final String COMPRESSED_CURRENT_ROUND_MESSAGE_OFFLOAD_HINT =
            "\n"
                + "<hint> The above is a compressed summary of the current round tool calls and"
                + " results. You should use this summary as context to continue reasoning and"
                + " answer the user's questions, rather than directly returning this compressed"
                + " content. The original detailed tool calls and results have been offloaded with"
                + " uuid: %s. If you need to retrieve the full original content for specific"
                + " details, you can use the context_reload tool with this UUID.</hint>";
}
