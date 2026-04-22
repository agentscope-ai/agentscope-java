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
                + "    - Preserve: tool name, exact arguments (with values), and a concise factual"
                + " summary of the output.\n"
                + "    - For repeated calls to the same tool:\n"
                + "        • Consolidate identical calls (same args, same result) into one entry"
                + " with frequency note.\n"
                + "        • Only list distinct argument combinations that led to different"
                + " outcomes.\n"
                + "        • Omit non-essential varying parameters (e.g., timestamps, request IDs)"
                + " if behavior is unchanged.\n"
                + "    - Treat a tool as a write/change operation if its name or output implies"
                + " side effects (e.g., contains 'write', 'update', 'delete', 'create', or returns"
                + " confirmation like 'written').\n"
                + "      For such operations, preserve critical details: file paths, data keys,"
                + " content snippets, state changes, and success/error indicators.\n"
                + "    - Output must be plain text—no markdown, JSON, bullets, headers, or"
                + " meta-comments.\n"
                + "    - If any tool output appears truncated or corrupted, include '[TRUNCATED]'.";

    // ============================================================================
    // Strategy 2-3: Large Message Offloading
    // ============================================================================

    // ============================================================================
    // Strategy 4: Previous Round Conversation Summary
    // ============================================================================

    /** Prompt for summarizing previous round conversations. */
    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT =
            "You are an expert dialogue compressor for autonomous agents. Your task is to rewrite"
                + " the assistant's final response from the previous round as a self-contained,"
                + " concise reply that incorporates all essential facts learned during the"
                + " round—without referencing tools, functions, or internal execution steps.\n"
                + "\n"
                + "Input includes: the user's original question, the assistant's original response,"
                + " and the results of any tool executions that informed that response.\n"
                + "\n"
                + "Your output will REPLACE the original assistant message in the conversation"
                + " history, forming a clean USER -> ASSISTANT pair for future context.\n"
                + "\n"
                + "Guidelines:\n"
                + "  - NEVER mention tools, functions, API calls, or execution steps (e.g., avoid"
                + " 'I called...', 'The system returned...', 'After running X...').\n"
                + "  - INSTEAD, state all findings as direct, factual knowledge the assistant now"
                + " possesses.\n"
                + "  - PRESERVE CRITICAL FACTS from tool results, especially:\n"
                + "      • File paths and their contents, changes, or creation (e.g.,"
                + " '/etc/app.conf sets port=8080')\n"
                + "      • Exact error messages when diagnostic (e.g., 'Permission denied (errno"
                + " 13)', 'timeout after 30s')\n"
                + "      • IDs, URLs, ports, status codes, configuration values, and data keys\n"
                + "      • Outcomes of write/change operations (e.g., 'Wrote maintenance flag to"
                + " /tmp/status', 'Updated user_id=789 email in database')\n"
                + "      • Service states or process info (e.g., 'auth-service is stopped',"
                + " 'PID=4567')\n"
                + "  - If an action was performed (e.g., file written, service restarted), clearly"
                + " state WHAT changed and WHERE.\n"
                + "  - If something failed or was incomplete, specify the limitation (e.g., 'Could"
                + " not restart: permission denied').\n"
                + "  - Consolidate redundant information; omit generic success messages with no"
                + " actionable detail.\n"
                + "  - Use clear, informative language—avoid meta-phrases like 'Based on logs...'"
                + " or 'As observed...'.\n"
                + "  - Output must be plain text: no markdown, bullets, JSON, XML, or section"
                + " headers.";

    /** Format for context offload tag. */
    public static final String CONTEXT_OFFLOAD_TAG_FORMAT = "<!-- CONTEXT_OFFLOAD: uuid=%s -->";

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
            "You are an expert context consolidator for autonomous agents. Your task is to"
                + " integrate new tool execution results into the current conversation context.\n"
                + "\n"
                + "INPUT STRUCTURE:\n"
                + "- The input consists of:\n"
                + "  (a) Optionally, a prior compressed context block ending with <!--"
                + " CONTEXT_OFFLOAD: uuid=... -->\n"
                + "  (b) Followed by zero or more alternating tool_use and tool_result messages"
                + " from the current turn.\n"
                + "- There is NO user message in the input.\n"
                + "- Plan-related tools have already been filtered out upstream.\n"
                + "\n"
                + "YOUR WORKFLOW:\n"
                + "1. IF the input contains a line matching <!-- CONTEXT_OFFLOAD: uuid=... -->:\n"
                + "   - Preserve all text BEFORE this line exactly as the prior context.\n"
                + "   - Process ONLY the tool_use/tool_result pairs AFTER this line.\n"
                + "2. ELSE (no offload marker found):\n"
                + "   - Treat the entire input as new tool interactions from the first compression"
                + " round.\n"
                + "   - Generate a summary based solely on these tool calls and their results.\n"
                + "3. For each tool_use/tool_result pair:\n"
                + "   - Summarize as a factual, first-person statement:\n"
                + "     \"I called [tool_name] with [arg1=value1, ...]; it returned: [key"
                + " details].\"\n"
                + "   - Preserve all technical specifics: file paths, IDs, error codes, config"
                + " values, state changes.\n"
                + "   - If a result is truncated or malformed, include it verbatim prefixed with"
                + " [UNPARSED OUTPUT].\n"
                + "\n"
                + "OUTPUT REQUIREMENTS:\n"
                + "- A single plain-text block containing:\n"
                + "    [prior context (if any)]\\n"
                + "[new tool summaries]\n"
                + "- DO NOT include any <!-- CONTEXT_OFFLOAD --> tag in your output.\n"
                + "- DO NOT mention user requests, intentions, or questions (they are not in the"
                + " input).\n"
                + "- DO NOT use markdown, JSON, bullets, or phrases like \"as before\", \"new"
                + " actions:\".\n"
                + "- The output will be used as the new compressed context, and a new offload tag"
                + " will be appended externally.\n"
                + "\n"
                + "SAFE TO REMOVE FROM tool_result:\n"
                + "- Boilerplate text (licenses, auto-comments)\n"
                + "- Redundant success messages with no actionable data\n"
                + "- Repeated log prefixes (if core content is retained)\n"
                + "\n"
                + "STRICTLY AVOID:\n"
                + "- Including raw tool_use/tool_result JSON\n"
                + "- Re-compressing or altering prior context\n"
                + "- Adding any offload marker (old or new)";

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
