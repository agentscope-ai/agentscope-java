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
package io.agentscope.extensions.model.openaiofficial.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.JsonValue;
import com.openai.models.responses.FileSearchTool;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolSearchTool;
import com.openai.models.responses.WebSearchTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OpenAIServerTool}. */
class OpenAIServerToolTest {

    @Test
    void wrapsAllProviderExecutedBuiltInToolTypes() {
        assertType(
                OpenAIServerTool.WEB_SEARCH,
                Tool.ofWebSearch(
                        WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build()));
        assertType(
                OpenAIServerTool.CODE_INTERPRETER,
                Tool.ofCodeInterpreter(
                        Tool.CodeInterpreter.builder().container("container_1").build()));
        assertType(
                OpenAIServerTool.IMAGE_GENERATION,
                Tool.ofImageGeneration(Tool.ImageGeneration.builder().build()));
        assertType(
                OpenAIServerTool.TOOL_SEARCH,
                Tool.ofSearch(
                        ToolSearchTool.builder()
                                .type(JsonValue.from("tool_search"))
                                .parameters(JsonValue.from(Map.of("type", "object")))
                                .build()));
    }

    @Test
    void rejectsNull() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> OpenAIServerTool.of(null));

        assertTrue(exception.getMessage().contains("must not be null"));
    }

    @Test
    void rejectsLocalFunctionTool() {
        Tool tool =
                Tool.ofFunction(
                        FunctionTool.builder()
                                .name("local_tool")
                                .strict(false)
                                .parameters(FunctionTool.Parameters.builder().build())
                                .build());

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> OpenAIServerTool.of(tool));

        assertTrue(exception.getMessage().contains("provider-executed built-in tools"));
    }

    @Test
    void rejectsFileSearchTool() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                OpenAIServerTool.of(
                                        Tool.ofFileSearch(
                                                FileSearchTool.builder()
                                                        .vectorStoreIds(List.of("vs_1"))
                                                        .build())));

        assertTrue(exception.getMessage().contains("provider-executed built-in tools"));
    }

    @Test
    void rejectsRuntimeTool() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> OpenAIServerTool.of(Tool.ofLocalShell()));

        assertTrue(exception.getMessage().contains("local runtime tools"));
    }

    @Test
    void rejectsClientExecutedToolSearch() {
        Tool tool =
                Tool.ofSearch(
                        ToolSearchTool.builder()
                                .type(JsonValue.from("tool_search"))
                                .execution(ToolSearchTool.Execution.CLIENT)
                                .parameters(JsonValue.from(Map.of("type", "object")))
                                .build());

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> OpenAIServerTool.of(tool));

        assertTrue(exception.getMessage().contains("Client-executed tool_search"));
    }

    private static void assertType(String expected, Tool tool) {
        OpenAIServerTool serverTool = OpenAIServerTool.of(tool);

        assertEquals(expected, serverTool.getType());
        assertSame(tool, serverTool.toTool());
        assertSame(serverTool.toTool(), serverTool.toTool());
    }
}
