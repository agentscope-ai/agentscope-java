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

import reactor.core.publisher.Mono;

/**
 * Tool that prepares authoritative server-side input before deciding whether user confirmation is
 * required.
 *
 * <p>Preparation happens after the permission gate and before any tool in the model-produced batch
 * executes. A confirmation pause persists the prepared input so resume does not prepare again.
 */
public interface UserConfirmableTool extends AgentTool {

    /**
     * Prepare authoritative execution input and decide whether the call needs confirmation.
     *
     * @param param original model-produced tool call and runtime context
     * @return prepared confirmation descriptor
     */
    Mono<ToolConfirmation> prepareConfirmation(ToolCallParam param);
}
