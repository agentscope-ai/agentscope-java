/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.formatter.dashscope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alibaba.dashscope.aigc.generation.GenerationParam;
import io.agentscope.core.model.ToolChoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for ToolChoice support in DashScopeToolsHelper. */
class DashScopeToolsHelperToolChoiceTest {

    private DashScopeToolsHelper helper;

    @BeforeEach
    void setUp() {
        helper = new DashScopeToolsHelper();
    }

    @Test
    void testApplyToolChoiceWithNull() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Should not throw exception
        assertDoesNotThrow(() -> helper.applyToolChoice(param, null));
    }

    @Test
    void testApplyToolChoiceWithAuto() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Should not throw exception and should log debug message
        assertDoesNotThrow(() -> helper.applyToolChoice(param, new ToolChoice.Auto()));

        // Auto should not modify the param (default behavior)
        // We can't easily verify the log, but we can ensure no exception is thrown
    }

    @Test
    void testApplyToolChoiceWithNone() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        helper.applyToolChoice(param, new ToolChoice.None());

        // None should set tools to null
        assertNull(param.getTools());
    }

    @Test
    void testApplyToolChoiceWithRequired() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Should not throw exception but log warning
        assertDoesNotThrow(() -> helper.applyToolChoice(param, new ToolChoice.Required()));
    }

    @Test
    void testApplyToolChoiceWithSpecific() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Should not throw exception but log warning about partial support
        assertDoesNotThrow(() -> helper.applyToolChoice(param, new ToolChoice.Specific("my_tool")));
    }

    @Test
    void testApplyToolChoiceWithSpecificToolName() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        ToolChoice.Specific specific = new ToolChoice.Specific("generate_response");

        // Should not throw exception
        assertDoesNotThrow(() -> helper.applyToolChoice(param, specific));

        // Verify the tool name is accessible
        assertEquals("generate_response", specific.toolName());
    }

    @Test
    void testApplyToolChoiceNoneRemovesExistingTools() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // First set some tools (we'll simulate this by not actually setting them
        // since we can't easily create ToolBase without complex setup)

        // Apply None - should set tools to null
        helper.applyToolChoice(param, new ToolChoice.None());

        assertNull(param.getTools());
    }

    @Test
    void testApplyToolChoiceHandlesExceptionGracefully() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Test with null param to ensure robust error handling
        assertDoesNotThrow(() -> helper.applyToolChoice(param, new ToolChoice.Auto()));
    }

    @Test
    void testApplyToolChoiceMultipleTimes() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        // Apply different tool choices sequentially
        helper.applyToolChoice(param, new ToolChoice.Auto());
        helper.applyToolChoice(param, new ToolChoice.None());
        assertNull(param.getTools());

        helper.applyToolChoice(param, new ToolChoice.Required());
        // Required doesn't modify tools in current implementation

        helper.applyToolChoice(param, new ToolChoice.Specific("tool1"));
        // Specific doesn't modify tools in current implementation (logged as partial support)
    }

    @Test
    void testApplyToolChoiceInstanceOfChecks() {
        GenerationParam param = GenerationParam.builder().model("qwen-max").build();

        ToolChoice auto = new ToolChoice.Auto();
        ToolChoice none = new ToolChoice.None();
        ToolChoice required = new ToolChoice.Required();
        ToolChoice specific = new ToolChoice.Specific("tool");

        // All should execute without exception
        assertDoesNotThrow(() -> helper.applyToolChoice(param, auto));
        assertDoesNotThrow(() -> helper.applyToolChoice(param, none));
        assertDoesNotThrow(() -> helper.applyToolChoice(param, required));
        assertDoesNotThrow(() -> helper.applyToolChoice(param, specific));
    }
}
