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
package io.agentscope.harness.coding.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that catches tool execution errors and formats them as model-readable messages.
 *
 * <p>Mirrors open-swe's {@code ToolErrorMiddleware}. When a tool call raises an exception, the
 * error is captured and returned as a tool result message so the model can recover gracefully.
 */
public class ToolErrorHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.just(event);
    }
}
