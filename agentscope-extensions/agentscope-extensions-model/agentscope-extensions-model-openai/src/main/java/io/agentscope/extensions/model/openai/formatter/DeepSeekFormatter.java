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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Formatter for DeepSeek Chat models (deepseek-chat, deepseek-coder).
 *
 * <p>DeepSeek API has the following specific requirements:
 * <ul>
 *   <li>No name field in messages (returns HTTP 400 if present)</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>In thinking mode, reasoning_content is preserved for all assistant messages and
 *       backfilled with an empty value when missing</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new DeepSeekFormatter())
 *     .modelName("deepseek-chat")
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @deprecated use {@link io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter}.
 * @see <a href="https://api-docs.deepseek.com/guides/thinking_mode#tool-calls">DeepSeek Thinking Mode</a>
 */
@Deprecated
public class DeepSeekFormatter extends OpenAIChatFormatter {

    private final boolean appendEmptyUserIfEndsWithAssistant;

    public DeepSeekFormatter() {
        this(false);
    }

    /**
     * Create a DeepSeek formatter with optional empty user message appending.
     *
     * @param appendEmptyUserIfEndsWithAssistant if true, append an empty user message when the
     *     conversation ends with an assistant message to avoid API errors
     */
    public DeepSeekFormatter(boolean appendEmptyUserIfEndsWithAssistant) {
        super();
        this.appendEmptyUserIfEndsWithAssistant = appendEmptyUserIfEndsWithAssistant;
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        return doFormat(msgs, null);
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs, GenerateOptions options) {
        List<OpenAIMessage> messages = super.doFormat(msgs, options);
        messages = applyDeepSeekFixes(messages, options);
        if (appendEmptyUserIfEndsWithAssistant) {
            messages = appendEmptyUserIfNeeded(messages);
        }
        return messages;
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    /**
     * Apply DeepSeek-specific message format fixes.
     *
     * <p>DeepSeek API requires (thinking mode, requests carrying tools): reasoning_content
     * must be fully passed back for <b>all</b> assistant turns — even turns without tool
     * calls; otherwise the API returns HTTP 400 ("The reasoning_content in the thinking mode
     * must be passed back to the API"). Passing reasoning_content is ignored by the API when
     * not required, so this method never strips it and backfills an empty value for assistant
     * messages that lack it. Framework-synthesized assistant messages (e.g. subagent
     * completion notifications) carry no ThinkingBlock and would otherwise be sent without
     * the field.
     *
     * <p>Thinking mode is decided by the request's {@code thinking} option ({@code
     * thinking={"type": "enabled"|"disabled"}} in the GenerateOptions additional body params,
     * as encoded by DeepSeekModelProvider). An explicitly disabled option opts out: only the
     * legacy name-field removal then applies. When the option is absent (unknown state) or
     * explicitly enabled, thinking mode is assumed: DeepSeek enables thinking server-side by
     * default, and a missing reasoning_content trace usually means the trace was lost (memory
     * compaction, framework-synthesized assistant turns) rather than that thinking mode is
     * off.
     *
     * <p>Mutation contract: elements of the given list are modified in place (legacy
     * name-field removal on every message, backfilled reasoning_content for assistant turns)
     * and the very same list instance is returned; callers must own the list — all
     * production callers pass the freshly converted list produced by {@code super.doFormat(...)}.
     *
     * <p>This method is static to allow sharing with {@link DeepSeekMultiAgentFormatter}.
     *
     * @param messages the OpenAI messages to fix in place; must be owned by the caller
     * @param options the effective generation options (may be null); the {@code thinking}
     *     additional body param, when present, decides the thinking mode
     * @return the same list instance with fixes applied
     * @see <a href="https://api-docs.deepseek.com/guides/thinking_mode#tool-calls">DeepSeek
     *     Thinking Mode / Tool Calls</a>
     */
    static List<OpenAIMessage> applyDeepSeekFixes(
            List<OpenAIMessage> messages, GenerateOptions options) {
        // Unknown state (no thinking option) is treated as thinking-on; see the javadoc.
        boolean thinkingMode = !Boolean.FALSE.equals(thinkingEnabledFromOptions(options));
        for (OpenAIMessage msg : messages) {
            fixMessage(msg, thinkingMode);
        }
        return messages;
    }

    /**
     * Reads the request's thinking flag from the GenerateOptions additional body params.
     *
     * <p>DeepSeek encodes it as {@code thinking={"type": "enabled"|"disabled"}} (see
     * DeepSeekModelProvider default options); any other shape is treated as unknown.
     *
     * @param options the generation options (may be null)
     * @return {@code TRUE}/{@code FALSE} when the flag is present, null when unknown
     */
    private static Boolean thinkingEnabledFromOptions(GenerateOptions options) {
        if (options == null) {
            // GenerateOptions normalizes additionalBodyParams to an empty map, so only a
            // null options object needs guarding here.
            return null;
        }
        Object thinking = options.getAdditionalBodyParams().get("thinking");
        if (thinking instanceof Map<?, ?> thinkingMap) {
            Object type = thinkingMap.get("type");
            if ("enabled".equals(type)) {
                return Boolean.TRUE;
            }
            if ("disabled".equals(type)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /**
     * Append an empty user message if the conversation ends with an assistant message.
     *
     * <p>Some DeepSeek API scenarios require the conversation to not end with an assistant message.
     *
     * @param messages the messages to check
     * @return messages with an empty user message appended if needed
     */
    static List<OpenAIMessage> appendEmptyUserIfNeeded(List<OpenAIMessage> messages) {
        if (messages.isEmpty()
                || !"assistant".equals(messages.get(messages.size() - 1).getRole())) {
            return messages;
        }
        List<OpenAIMessage> result = new ArrayList<>(messages);
        result.add(OpenAIMessage.builder().role("user").content("").build());
        return result;
    }

    private static void fixMessage(OpenAIMessage msg, boolean thinkingMode) {
        // Legacy rule: drop the name field (DeepSeek returns HTTP 400 when it is present).
        msg.setName(null);
        // Thinking mode: assistant messages missing reasoning_content must be backfilled so
        // requests carrying tools do not fail with HTTP 400 (see applyDeepSeekFixes javadoc).
        // Messages are mutated in place: every caller passes a freshly converted list from
        // super.doFormat(...), so no other code observes the intermediate objects and no
        // field can be dropped by a manual rebuild drifting out of sync with the DTO.
        if (thinkingMode
                && "assistant".equals(msg.getRole())
                && msg.getReasoningContent() == null) {
            msg.setReasoningContent("");
        }
    }
}
