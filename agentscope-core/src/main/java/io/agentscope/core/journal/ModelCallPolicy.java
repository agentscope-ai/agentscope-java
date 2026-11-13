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
package io.agentscope.core.journal;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;

/** Optional hosted admission/accounting policy, shared with inherited child execution contexts. */
public interface ModelCallPolicy {
    String CONTEXT_KEY = "agentscope.journal.modelPolicy";

    /** False declares that admission is a no-op; the runtime does not enqueue a task. */
    default boolean hasBeforeCall() {
        return true;
    }

    /** False declares that completion accounting is a no-op. */
    default boolean hasAfterCall() {
        return true;
    }

    void beforeCall(String callId, String model, RuntimeContext context);

    void afterCall(
            String callId, String model, String status, ChatUsage usage, RuntimeContext context);
}
