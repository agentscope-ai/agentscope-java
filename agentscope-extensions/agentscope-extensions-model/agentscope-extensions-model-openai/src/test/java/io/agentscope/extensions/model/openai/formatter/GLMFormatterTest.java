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
package io.agentscope.extensions.model.openai.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAITool;
import io.agentscope.extensions.model.openai.dto.OpenAIToolFunction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Backward-compatibility tests for the deprecated {@link GLMFormatter} and
 * {@link GLMMultiAgentFormatter}.
 *
 * <p>The full behavior test suite lives next to the new implementations in
 * {@code io.agentscope.extensions.model.openai.compat.glm}. These tests only verify that the
 * deprecated classes still behave like (and are substitutable for) the new ones.
 */
@SuppressWarnings("deprecation")
@Tag("unit")
@DisplayName("Deprecated GLM formatter Backward Compatibility Tests")
class GLMFormatterTest {

    @Test
    @DisplayName("Deprecated GLMFormatter should be an instance of the new implementation")
    void testDeprecatedFormatterIsNewImplementation() {
        assertTrue(
                new GLMFormatter()
                        instanceof io.agentscope.extensions.model.openai.compat.glm.GLMFormatter);
        assertTrue(
                new GLMMultiAgentFormatter()
                        instanceof
                        io.agentscope.extensions.model.openai.compat.glm.GLMMultiAgentFormatter);
    }

    @Test
    @DisplayName("Deprecated GLMFormatter should still ensure a user message")
    void testFormatEnsuresUserMessage() {
        GLMFormatter formatter = new GLMFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You are helpful")
                                                        .build()))
                                .build());

        List<OpenAIMessage> result = formatter.format(messages);

        assertEquals(2, result.size());
        assertEquals("system", result.get(0).getRole());
        assertEquals("user", result.get(1).getRole());
    }

    @Test
    @DisplayName("Deprecated static ensureUserMessage should delegate to the new implementation")
    void testStaticEnsureUserMessageDelegates() {
        List<OpenAIMessage> messages =
                List.of(OpenAIMessage.builder().role("assistant").content("Hi").build());

        List<OpenAIMessage> result = GLMFormatter.ensureUserMessage(messages);

        assertEquals(2, result.size());
        assertEquals("user", result.get(1).getRole());
    }

    @Test
    @DisplayName("Deprecated static applyGLMToolChoice should degrade to auto")
    void testStaticApplyGLMToolChoiceDelegates() {
        OpenAIToolFunction function = new OpenAIToolFunction();
        function.setName("test_tool");
        OpenAITool tool = new OpenAITool();
        tool.setFunction(function);
        tool.setType("function");

        OpenAIRequest request =
                OpenAIRequest.builder()
                        .model("glm-4.7")
                        .messages(List.of())
                        .tools(List.of(tool))
                        .build();

        GLMFormatter.applyGLMToolChoice(request, new ToolChoice.Required());

        assertEquals("auto", request.getToolChoice());
    }

    @Test
    @DisplayName("Deprecated GLMFormatter should still drop the strict parameter")
    void testApplyToolsWithoutStrict() {
        GLMFormatter formatter = new GLMFormatter();

        OpenAIRequest request =
                OpenAIRequest.builder().model("glm-4.7").messages(List.of()).build();

        ToolSchema tool =
                ToolSchema.builder()
                        .name("test_tool")
                        .description("Test tool")
                        .strict(true)
                        .build();

        formatter.applyTools(request, List.of(tool));

        assertNotNull(request.getTools());
        assertNull(request.getTools().get(0).getFunction().getStrict());
    }

    @Test
    @DisplayName("Deprecated GLMMultiAgentFormatter should still ensure a user message")
    void testMultiAgentFormatEnsuresUserMessage() {
        GLMMultiAgentFormatter formatter = new GLMMultiAgentFormatter();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You are helpful")
                                                        .build()))
                                .build());

        List<OpenAIMessage> result = formatter.format(messages);

        assertEquals(2, result.size());
        assertEquals("user", result.get(1).getRole());
    }
}
