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
package io.agentscope.core.formatter.gemini.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("GeminiRequest Unit Tests")
class GeminiRequestTest {

    @Test
    @DisplayName("Should set all fields through builder")
    void testBuilder() {
        GeminiContent content =
                new GeminiContent(
                        "user", List.of(createTextPart("hello"), createTextPart("world")));

        GeminiTool tool = new GeminiTool();
        GeminiTool.GeminiFunctionDeclaration declaration =
                new GeminiTool.GeminiFunctionDeclaration();
        declaration.setName("lookup");
        declaration.setDescription("Lookup value");
        tool.setFunctionDeclarations(List.of(declaration));

        GeminiToolConfig.GeminiFunctionCallingConfig callingConfig =
                new GeminiToolConfig.GeminiFunctionCallingConfig();
        callingConfig.setMode("AUTO");
        callingConfig.setAllowedFunctionNames(List.of("lookup"));
        GeminiToolConfig toolConfig = new GeminiToolConfig();
        toolConfig.setFunctionCallingConfig(callingConfig);

        GeminiSafetySetting safetySetting = new GeminiSafetySetting();
        safetySetting.setCategory("HARM_CATEGORY_HARASSMENT");
        safetySetting.setThreshold("BLOCK_ONLY_HIGH");

        GeminiGenerationConfig generationConfig = new GeminiGenerationConfig();
        generationConfig.setMaxOutputTokens(256);

        GeminiRequest request =
                GeminiRequest.builder()
                        .contents(List.of(content))
                        .tools(List.of(tool))
                        .toolConfig(toolConfig)
                        .safetySettings(List.of(safetySetting))
                        .systemInstruction(
                                new GeminiContent("user", List.of(createTextPart("sys"))))
                        .generationConfig(generationConfig)
                        .build();

        assertEquals(1, request.getContents().size());
        assertEquals(1, request.getTools().size());
        assertEquals(toolConfig, request.getToolConfig());
        assertEquals(1, request.getSafetySettings().size());
        assertNotNull(request.getSystemInstruction());
        assertEquals(generationConfig, request.getGenerationConfig());
    }

    @Test
    @DisplayName("Should set all fields through setters")
    void testSetters() {
        GeminiRequest request = new GeminiRequest();

        GeminiContent content = new GeminiContent("user", List.of(createTextPart("ping")));
        GeminiTool tool = new GeminiTool();
        GeminiToolConfig toolConfig = new GeminiToolConfig();
        GeminiSafetySetting safetySetting = new GeminiSafetySetting();
        GeminiContent systemInstruction = new GeminiContent("user", List.of(createTextPart("sys")));
        GeminiGenerationConfig generationConfig = new GeminiGenerationConfig();

        request.setContents(List.of(content));
        request.setTools(List.of(tool));
        request.setToolConfig(toolConfig);
        request.setSafetySettings(List.of(safetySetting));
        request.setSystemInstruction(systemInstruction);
        request.setGenerationConfig(generationConfig);

        assertEquals(List.of(content), request.getContents());
        assertEquals(List.of(tool), request.getTools());
        assertEquals(toolConfig, request.getToolConfig());
        assertEquals(List.of(safetySetting), request.getSafetySettings());
        assertEquals(systemInstruction, request.getSystemInstruction());
        assertEquals(generationConfig, request.getGenerationConfig());
    }

    @Test
    @DisplayName("Should serialize using expected Gemini request field names")
    void testSerialization() {
        GeminiRequest request =
                GeminiRequest.builder()
                        .contents(
                                List.of(
                                        new GeminiContent(
                                                "user", List.of(createTextPart("hello")))))
                        .generationConfig(new GeminiGenerationConfig())
                        .build();

        String json = JsonUtils.getJsonCodec().toJson(request);

        assertTrue(json.contains("\"contents\""));
        assertTrue(json.contains("\"generationConfig\""));
        assertTrue(json.contains("\"role\":\"user\""));
        assertTrue(json.contains("\"parts\""));
        assertTrue(!json.contains("\"toolConfig\""));
    }

    private static GeminiPart createTextPart(String text) {
        GeminiPart part = new GeminiPart();
        part.setText(text);
        return part;
    }
}
