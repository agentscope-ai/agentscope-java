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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import java.util.List;

/** Message-only compatibility entry point for the default Harness token estimator. */
public class TokenCounterUtil {

    /**
     * Estimates message tokens with the same text, tool and media rules as final input checks.
     *
     * @param messages messages to estimate; null or empty lists count as zero
     * @return estimated tokens, capped at the legacy integer return type's maximum
     */
    public static int calculateToken(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long tokens =
                ContextTokenEstimator.approximate()
                        .estimate(new ModelCallInput(messages, List.of(), null, null))
                        .tokens();
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /**
     * Sizes the conversation from the usage the provider reported for the most recent measured
     * turn instead of estimating it.
     *
     * <p>Estimation is biased in both directions and no character ratio fixes both: non-ASCII
     * prose is undercounted, while tool-heavy conversations are overcounted because every message
     * carries a fixed overhead larger than its own text. The provider's own count has neither
     * error, and it is already on the message as {@link Msg#getChatUsage()}.
     *
     * <p>The reported {@code inputTokens} covered the whole prompt of that request, fixed inputs
     * included, and its {@code outputTokens} covered the reply that is now the last message of
     * that turn. Together they therefore measure everything up to and including the reporting
     * message; only the messages appended after it still need estimating, so they are added on top.
     *
     * @param conversation non-SYSTEM messages in request order
     * @param fixedTokens estimated cost of the fixed inputs already charged separately
     * @param estimator estimator used for messages appended after the reporting turn; a null
     *     value falls back to {@link ContextTokenEstimator#approximate()}
     * @return conversation tokens implied by the reported usage, or {@code -1} when the
     *     conversation carries no usable report and the caller must estimate instead
     */
    public static long conversationTokensFromReportedUsage(
            List<Msg> conversation, long fixedTokens, ContextTokenEstimator estimator) {
        if (conversation == null || conversation.isEmpty()) {
            return -1;
        }
        for (int index = conversation.size() - 1; index >= 0; index--) {
            ChatUsage usage = conversation.get(index).getChatUsage();
            if (usage == null) {
                continue;
            }
            long reported = (long) usage.getInputTokens() + usage.getOutputTokens();
            if (reported <= 0) {
                continue;
            }
            // The report already includes the fixed inputs of that turn. If today's fixed inputs
            // have grown past the whole report, the subtraction would undercount the conversation
            // and delay compaction past the window, so estimation is the safer answer.
            long reportedConversation = reported - fixedTokens;
            if (reportedConversation < 0) {
                return -1;
            }
            List<Msg> appended = conversation.subList(index + 1, conversation.size());
            long appendedTokens =
                    appended.isEmpty()
                            ? 0
                            : (estimator == null ? ContextTokenEstimator.approximate() : estimator)
                                    .estimate(new ModelCallInput(appended, List.of(), null, null))
                                    .tokens();
            return Math.max(0, reportedConversation + appendedTokens);
        }
        return -1;
    }
}
