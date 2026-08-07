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

/** Prompt templates used by auto context compression. */
public final class Prompts {
    private Prompts() {}

    public static final String COMPRESSION_MESSAGE_LIST_END =
            "Above is the message list that needs to be compressed.";

    public static final String PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT =
            "Compress the tool call history and keep tool names, arguments, and key results.";

    public static final String PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT =
            "Rewrite the older conversation into a concise, factual summary.";

    public static final String CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT =
            "Summarize this oversized message while keeping key facts.";

    public static final String CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT =
            "Compress the current round into a short, factual context.";

    public static final String CONTEXT_OFFLOAD_TAG_FORMAT = "<!-- CONTEXT_OFFLOAD: uuid=%s -->";
}
