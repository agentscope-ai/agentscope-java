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

public class Prompts {

    public static final String DEFAULT_TOOL_COMPRESS_PROMPT_START =
            "Please intelligently compress and summarize the following tool invocation history";
    public static final String DEFAULT_TOOL_COMPRESS_PROMPT_END =
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

    public static final String DEFAULT_CONVERSATION_SUMMARY_PROMPT_START =
            "Please intelligently summarize the following conversation history. Preserve key"
                    + " information, decisions, and context that would be important for future"
                    + " reference.";

    public static final String DEFAULT_CONVERSATION_SUMMARY_PROMPT_END =
            "Above is a conversation history. \n"
                    + "Please provide a concise summary that:\n"
                    + "    - Preserves important decisions, conclusions, and key information\n"
                    + "    - Maintains context that would be needed for future interactions\n"
                    + "    - Consolidates repeated or similar information\n"
                    + "    - Highlights any important outcomes or results";

    public static final String CURRENT_ROUND_COMPRESS_PROMPT =
            "Please compress and summarize the following current round tool calls and results,"
                    + " preserving key information:\n"
                    + "- Tool names and IDs\n"
                    + "- Important parameters\n"
                    + "- Key results and outcomes\n"
                    + "- Any errors or important status information\n\n"
                    + "%s"
                    + "\n\nProvide a concise summary that preserves all critical information.";

    public static final String COMPRESSED_CURRENT_ROUND_FORMAT =
            "<compressed_current_round>%s</compressed_current_round>%s";

    public static final String COMPRESSED_CURRENT_ROUND_OFFLOAD_HINT =
            "\n<hint> Original tool calls and results offloaded with uuid: %s</hint>";

    public static final String CONVERSATION_SUMMARY_FORMAT =
            "<conversation_summary>%s</conversation_summary>\n"
                    + "<hint> This is a summary of previous conversation rounds. You can use this"
                    + " information as historical context for future reference.\n";

    public static final String CONVERSATION_SUMMARY_OFFLOAD_HINT =
            "<hint> The original conversation is stored in the offload "
                    + "with working_context_offload_uuid: %s. If you need to retrieve the full"
                    + " conversation, please use the context_reload tool with this UUID.</hint>";

    public static final String OFFLOAD_HINT_FORMAT =
            "%s\n"
                    + "<hint> This message content has been offloaded due to large"
                    + " size. The original content is stored with"
                    + " working_context_offload_uuid: %s. If you need to retrieve"
                    + " the full content, please use the context_reload tool with"
                    + " this UUID.</hint>";

    public static final String COMPRESSED_HISTORY_FORMAT =
            "<compressed_history>%s</compressed_history>\n"
                    + "<hint> You can use this information as historical context for future"
                    + " reference in carrying out your tasks\n";

    public static final String COMPRESSED_HISTORY_OFFLOAD_HINT =
            "<hint> The original tools invocation is stored in the offload"
                    + " with working_context_offload_uuid: %s. if you need to retrieve it, please"
                    + " use the context offload tool to get it. \n";
}
