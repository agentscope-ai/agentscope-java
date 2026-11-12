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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

/**
 * Callback for tool invocation lifecycle notifications.
 *
 * <p>All methods default to no-ops and may be overridden as needed.
 */
public interface ToolExecutionCallback {
    /**
     * Notify that a tool invocation is starting — fired when the executor begins the invocation
     * (when the call's turn arrives), not when the batch is admitted.
     *
     * @param call the tool invocation
     */
    default void onStart(ToolUseBlock call) {}

    /**
     * Report intermediate progress. A tool invocation may produce multiple progress notifications.
     *
     * @param call the tool invocation
     * @param progress the progress content
     */
    default void onProgress(ToolUseBlock call, ToolResultBlock progress) {}

    /**
     * Report the result of a tool invocation, which may indicate success, error, or suspension.
     * Notified at most once per invocation, as soon as the result resolves and before ordered
     * batch aggregation.
     *
     * @param call the tool invocation
     * @param result the invocation result
     */
    default void onResult(ToolUseBlock call, ToolResultBlock result) {}
}
