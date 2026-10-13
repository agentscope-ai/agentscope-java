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
package io.agentscope.extensions.model.gemini.tool;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GeminiServerTool}. */
class GeminiServerToolTest {

    @Test
    void wrapsSdkToolDefinition() {
        Tool tool =
                Tool.builder()
                        .googleSearch(
                                GoogleSearch.builder()
                                        .excludeDomains(List.of("example.com"))
                                        .build())
                        .build();

        GeminiServerTool serverTool = GeminiServerTool.of(tool);

        assertSame(tool, serverTool.toTool());
        assertSame(serverTool.toTool(), serverTool.toTool());
    }

    @Test
    void rejectsNullSdkToolDefinition() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> GeminiServerTool.of(null));

        assertTrue(exception.getMessage().contains("must not be null"));
    }

    @Test
    void rejectsLocalFunctionToolDefinition() {
        Tool tool =
                Tool.builder()
                        .functionDeclarations(
                                List.of(FunctionDeclaration.builder().name("local_tool").build()))
                        .build();

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> GeminiServerTool.of(tool));

        assertTrue(exception.getMessage().contains("local function tools"));
    }
}
