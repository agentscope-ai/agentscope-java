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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolResultBlockTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyStateConstructorDefaultsToLocalResult() {
        ToolResultBlock result =
                new ToolResultBlock(
                        "tool-call-0",
                        "local-tool",
                        List.of(TextBlock.builder().text("ok").build()),
                        Map.of(),
                        ToolResultState.SUCCESS);

        assertFalse(result.isServer());
        assertEquals(ToolResultState.SUCCESS, result.getState());
    }

    @Test
    void errorFactoryCreatesStructuredErrorState() {
        ToolResultBlock result = ToolResultBlock.error("probe failed");

        assertEquals(ToolResultState.ERROR, result.getState());
        TextBlock output = assertInstanceOf(TextBlock.class, result.getOutput().get(0));
        assertEquals("Error: probe failed", output.getText());
        assertFalse(result.isServer());
    }

    @Test
    void builderMarksServerSideToolResult() {
        ToolResultBlock serverResult =
                ToolResultBlock.builder()
                        .id("tool-call-1")
                        .name("GOOGLE_SEARCH_WEB")
                        .output(TextBlock.builder().text("response").build())
                        .server(true)
                        .build();

        assertTrue(serverResult.isServer());
        assertEquals("tool-call-1", serverResult.getId());
        assertEquals("GOOGLE_SEARCH_WEB", serverResult.getName());

        ToolResultBlock localResult =
                ToolResultBlock.builder()
                        .id("tool-call-2")
                        .name("local-tool")
                        .output(TextBlock.builder().text("ok").build())
                        .build();
        assertFalse(localResult.isServer());
    }

    @Test
    void serverFlagSerializesToJson() throws JsonProcessingException {
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id("tool-call-3")
                        .name("GOOGLE_SEARCH_WEB")
                        .output(TextBlock.builder().text("response").build())
                        .server(true)
                        .build();

        String json = objectMapper.writeValueAsString(result);
        assertTrue(json.contains("\"server\":true"), "Expected server field in JSON: " + json);

        ToolResultBlock deserialized = objectMapper.readValue(json, ToolResultBlock.class);
        assertTrue(deserialized.isServer());
    }

    @Test
    void serverFlagDefaultsToFalseWhenAbsentInJson() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_result",
                    "id": "tool-call-4",
                    "name": "local-tool",
                    "output": [{"type": "text", "text": "ok"}]
                }
                """;

        ToolResultBlock result = objectMapper.readValue(json, ToolResultBlock.class);
        assertFalse(result.isServer());
    }

    @Test
    void withStatePreservesServerFlag() {
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id("tool-call-5")
                        .name("GOOGLE_SEARCH_WEB")
                        .output(TextBlock.builder().text("response").build())
                        .server(true)
                        .build();

        ToolResultBlock updated = result.withState(ToolResultState.SUCCESS);

        assertTrue(updated.isServer());
        assertEquals(ToolResultState.SUCCESS, updated.getState());
    }

    @Test
    void withIdAndNamePreservesServerFlag() {
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .output(TextBlock.builder().text("response").build())
                        .server(true)
                        .build();

        ToolResultBlock updated = result.withIdAndName("tool-call-6", "GOOGLE_SEARCH_WEB");

        assertTrue(updated.isServer());
        assertEquals("tool-call-6", updated.getId());
        assertEquals("GOOGLE_SEARCH_WEB", updated.getName());
    }

    @Test
    void toolCallErrorFactoryPreservesToolIdAndRuntimeErrorMarker() {
        ToolResultBlock result = ToolResultBlock.error("tool-1", "probe failed");

        assertEquals("tool-1", result.getId());
        assertEquals(ToolResultState.ERROR, result.getState());
        TextBlock output = assertInstanceOf(TextBlock.class, result.getOutput().get(0));
        assertEquals("[ERROR] probe failed", output.getText());
    }
}
