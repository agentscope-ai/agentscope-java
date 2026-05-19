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
import io.agentscope.core.message.ContentBlock;

/**
 * Strategy interface for converting different types of ContentBlock into AG-UI events.
 *
 * @param <T> The specific type of ContentBlock this converter handles.
 */
public interface BlockEventConverter<T extends ContentBlock> {

    /**
     * Retrieves the specific type of ContentBlock that this converter can process.
     *
     * @return The class type of the ContentBlock handled by this converter.
     */
    Class<T> supportedBlockType();

    /**
     * Determines whether the current Event is applicable for this converter.
     *
     * @param event The agent stream event to evaluate.
     * @return true if the event can be processed by this converter, false otherwise.
     */
    boolean isApplicable(Event event);

    /**
     * Executes the conversion logic, generating and appending AG-UI events to the stream context.
     *
     * @param block   The content block to convert.
     * @param event   The original agent event.
     * @param context The stream context holding state and emitted events.
     */
    void convert(T block, Event event, StreamContext context);
}
