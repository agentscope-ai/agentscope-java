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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A content block that provides hints to the LLM during reasoning.
 *
 * <p>Hint blocks are injected by middleware or memory systems (e.g., RAG) to supply
 * contextual information that guides the agent's reasoning without being part of
 * the conversation history.
 */
public final class HintBlock extends ContentBlock {

    private final String id;
    private final String hint;

    @JsonCreator
    public HintBlock(@JsonProperty("id") String id, @JsonProperty("hint") String hint) {
        this.id = id;
        this.hint = hint;
    }

    /**
     * Gets the unique identifier of this hint block.
     *
     * @return The hint block ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the hint text.
     *
     * @return The hint content
     */
    public String getHint() {
        return hint;
    }
}
