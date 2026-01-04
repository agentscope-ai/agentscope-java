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
import java.util.function.BiConsumer;

/**
 * Default implementation of ToolEmitter that delivers chunks to a callback.
 *
 * <p>This class is package-private and created internally by the framework when a tool method
 * declares a ToolEmitter parameter. Each tool invocation gets its own DefaultToolEmitter instance.
 */
class DefaultToolEmitter implements ToolEmitter {

    private final ToolUseBlock toolUseBlock;
    private final BiConsumer<ToolUseBlock, ToolResultBlock> chunkCallback;

    /**
     * Create a DefaultToolEmitter.
     *
     * @param toolUseBlock The tool use block identifying the tool call
     * @param chunkCallback Callback to deliver chunks to hooks (may be null)
     */
    DefaultToolEmitter(
            ToolUseBlock toolUseBlock, BiConsumer<ToolUseBlock, ToolResultBlock> chunkCallback) {
        this.toolUseBlock = toolUseBlock;
        this.chunkCallback = chunkCallback;
    }

    /**
     * Emits a tool result chunk to the registered callback.
     *
     * <p>This implementation delivers the chunk to the callback function (which typically forwards
     * it to hooks via {@code onActingChunk()} events). If either the callback or chunk is null,
     * this method silently does nothing.
     *
     * @param chunk The tool result chunk to emit (may be null)
     */
    @Override
    public void emit(ToolResultBlock chunk) {
        if (chunkCallback != null && chunk != null) {
            chunkCallback.accept(toolUseBlock, chunk);
        }
    }
}
