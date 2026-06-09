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

import io.agentscope.core.message.Msg;
import java.util.List;

/** Lightweight token estimator for compression triggers. */
public final class TokenCounterUtil {
    private TokenCounterUtil() {}

    public static int calculateToken(List<Msg> messages) {
        int chars = MsgUtils.calculateMessagesCharCount(messages);
        return Math.max(1, chars / 4);
    }
}
