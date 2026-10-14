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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.context.TokenAccountingService;
import java.util.List;

/**
 * Estimates the token count of conversation messages for the compaction trigger.
 *
 * <p>The counting rules live in {@link TokenAccountingService} so the trigger and the final request
 * budget agree on the cost of the same message or media block. This class is a thin adapter kept
 * for its stable static signature.
 */
public class TokenCounterUtil {

    private static final TokenAccountingService SERVICE = TokenAccountingService.defaults();

    /**
     * Calculates the estimated total input tokens for a list of messages.
     *
     * @param messages the list of messages to estimate tokens for
     * @return estimated number of input tokens, saturating at {@link Integer#MAX_VALUE}
     */
    public static int calculateToken(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long tokens = SERVICE.countMessages(messages);
        return tokens > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tokens;
    }

    /**
     * Calculates the estimated token cost of a set of content blocks, excluding per-message
     * overhead. Used to price only the selected blocks that pruning will actually shrink.
     */
    public static int calculateBlockToken(List<ContentBlock> blocks) {
        long tokens = SERVICE.countBlocks(blocks);
        return tokens > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tokens;
    }

    /** Calculates the per-message overhead only (fixed cost plus role/name text). */
    public static int calculateMessageOverhead(Msg msg) {
        long tokens = SERVICE.messageOverhead(msg);
        return tokens > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tokens;
    }
}
