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
}
