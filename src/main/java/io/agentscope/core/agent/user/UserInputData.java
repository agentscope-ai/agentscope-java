/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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

    public UserInputData(List<ContentBlock> blocksInput, Map<String, Object> structuredInput) {
        this.blocksInput = blocksInput;
        this.structuredInput = structuredInput;
    }

    public List<ContentBlock> getBlocksInput() {
        return blocksInput;
    }

    public Map<String, Object> getStructuredInput() {
        return structuredInput;
    }
}
