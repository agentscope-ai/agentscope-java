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
package io.agentscope.core.agent.user;

import io.agentscope.core.message.ContentBlock;
import java.util.List;
import java.util.Map;

/**
 * Data class that holds user input information with dual representation.
 * Contains both content blocks (for message construction) and optional structured data
 * (for typed input validation). This dual nature allows flexible handling of simple text
 * input and complex structured forms within the same unified input system.
 */
public class UserInputData {

    private final List<ContentBlock> blocksInput;
    private final Map<String, Object> structuredInput;

    /**
     * Creates a new UserInputData instance.
     *
     * @param blocksInput List of content blocks representing the user input
     * @param structuredInput Optional structured data map for typed input (may be null)
     */
    public UserInputData(List<ContentBlock> blocksInput, Map<String, Object> structuredInput) {
        this.blocksInput = blocksInput;
        this.structuredInput = structuredInput;
    }

    /**
     * Gets the content blocks representing the user input.
     *
     * <p>Content blocks can include text, images, audio, or other multimodal content. This
     * representation is suitable for constructing message objects.
     *
     * @return List of content blocks (may be null or empty)
     */
    public List<ContentBlock> getBlocksInput() {
        return blocksInput;
    }

    /**
     * Gets the structured input data as a key-value map.
     *
     * <p>This optional representation allows for typed input validation and complex form handling.
     * For example, a form with name, age, and email fields can be represented as a map.
     *
     * @return Map of structured input data, or null if not provided
     */
    public Map<String, Object> getStructuredInput() {
        return structuredInput;
    }
}
