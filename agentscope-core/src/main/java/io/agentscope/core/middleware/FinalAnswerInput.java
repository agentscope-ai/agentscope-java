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
package io.agentscope.core.middleware;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;

/**
 * Input of the {@link MiddlewareBase#onFinalAnswer} consultation.
 *
 * @param candidate       the working draft this gate receives: the chain head sees the
 *                        model's original, later gates see their predecessors'
 *                        replacements. Not necessarily plain text: calls and results of
 *                        server-side tools (executed provider-side, e.g. web_search) are
 *                        embedded in the same assistant message, which is equally a
 *                        candidate; the returnDirect exit's candidate is the closing
 *                        message assembled by the framework (may contain non-text blocks)
 * @param retryCount      retries already honored in this call; 0 for the first candidate,
 *                        +1 per honored retry (a round with multiple vetoes counts once),
 *                        reset across calls
 * @param cumulativeUsage usage accumulated over all attempts of this call, not lost across
 *                        retries; {@code null} when the provider reports none
 * @param finishReason    which exit produced this output, see {@link FinishReason}
 * @author shanhongyu
 */
public record FinalAnswerInput(
        Msg candidate, int retryCount, ChatUsage cumulativeUsage, FinishReason finishReason) {}
