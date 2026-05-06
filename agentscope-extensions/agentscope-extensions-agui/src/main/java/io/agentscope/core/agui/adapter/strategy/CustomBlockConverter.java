/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter.strategy;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agui.adapter.StreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.CustomBlock;

/**
 * Converter for handling CustomBlock events, transforming them into AG-UI Custom events.
 */
public class CustomBlockConverter implements BlockEventConverter<CustomBlock> {

    @Override
    public boolean isApplicable(Event event) {
        // Custom events can occur at any stage, so always return true.
        return true;
    }

    @Override
    public void convert(CustomBlock block, Event event, StreamContext ctx) {
        ctx.emit(
                new AguiEvent.Custom(
                        ctx.getThreadId(), ctx.getRunId(), block.getName(), block.getValue()));
    }
}
