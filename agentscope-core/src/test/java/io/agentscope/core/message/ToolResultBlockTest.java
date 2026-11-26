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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolResultBlockTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void errorFactoryCreatesStructuredErrorState() {
        ToolResultBlock result = ToolResultBlock.error("probe failed");

        assertEquals(ToolResultState.ERROR, result.getState());
        TextBlock output = assertInstanceOf(TextBlock.class, result.getOutput().get(0));
        assertEquals("Error: probe failed", output.getText());
    }

    @Test
    void serverToolMarkerSurvivesJsonRoundTrip() throws JsonProcessingException {
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id("tool-call-3")
                        .name("GOOGLE_SEARCH_WEB")
                        .output(TextBlock.builder().text("response").build())
                        .metadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true))
                        .build();

        String json = objectMapper.writeValueAsString(result);
        assertTrue(
                json.contains("\"serverTool\":true"), "Expected server metadata in JSON: " + json);

        ToolResultBlock deserialized = objectMapper.readValue(json, ToolResultBlock.class);
        assertTrue(deserialized.isServerTool());
    }

    @Test
    void copyMethodsPreserveServerToolMarker() {
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id("tool-call-5")
                        .name("GOOGLE_SEARCH_WEB")
                        .output(TextBlock.builder().text("response").build())
                        .metadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true))
                        .build();

        ToolResultBlock updated = result.withState(ToolResultState.SUCCESS);
        assertTrue(updated.isServerTool());
        assertEquals(ToolResultState.SUCCESS, updated.getState());

        ToolResultBlock renamed = updated.withIdAndName("tool-call-6", "GOOGLE_SEARCH_WEB");
        assertTrue(renamed.isServerTool());
        assertEquals("tool-call-6", renamed.getId());
        assertEquals("GOOGLE_SEARCH_WEB", renamed.getName());
    }

    @Test
    void toolCallErrorFactoryPreservesToolIdAndRuntimeErrorMarker() {
        ToolResultBlock result = ToolResultBlock.error("tool-1", "probe failed");

        assertEquals("tool-1", result.getId());
        assertEquals(ToolResultState.ERROR, result.getState());
        TextBlock output = assertInstanceOf(TextBlock.class, result.getOutput().get(0));
        assertEquals("[ERROR] probe failed", output.getText());
    }

    @Test
    void withMetadataMergesWithoutChangingTheOriginalBlock() {
        ToolResultBlock original =
                ToolResultBlock.error("bad argument")
                        .withMetadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true));

        ToolResultBlock classified =
                original.withMetadata(
                        ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, "MEMORY.md"));

        assertEquals(ToolResultState.ERROR, classified.getState());
        assertEquals(ToolResultState.ERROR, original.getState());
        assertEquals(
                false, original.getMetadata().containsKey(ToolResultBlock.METADATA_ERROR_KIND));
        assertEquals(true, classified.isServerTool());
        assertEquals(
                "invalid_argument",
                classified.getMetadata().get(ToolResultBlock.METADATA_ERROR_KIND));
        assertEquals(
                "MEMORY.md", classified.getMetadata().get(ToolResultBlock.METADATA_ERROR_SCOPE));
        assertThrows(
                UnsupportedOperationException.class, () -> classified.getMetadata().put("x", "y"));
    }

    @Test
    void errorMetadataSurvivesCopyMethodsAndJsonRoundTrip() throws JsonProcessingException {
        ToolResultBlock result =
                ToolResultBlock.error("tool-1", "startLine must be >= 1")
                        .withMetadata(
                                ToolErrorMetadata.of(
                                        ToolErrorKind.INVALID_ARGUMENT, "MEMORY.md", false));

        ToolResultBlock inMessage = result.withIdAndName("tool-1", "memory_get");

        String json = objectMapper.writeValueAsString(inMessage);
        assertTrue(json.contains("\"agentscope_error_kind\":\"invalid_argument\""), json);
        assertTrue(json.contains("\"agentscope_error_scope\":\"MEMORY.md\""), json);
        assertTrue(json.contains("\"agentscope_retriable\":false"), json);

        ToolResultBlock deserialized = objectMapper.readValue(json, ToolResultBlock.class);
        assertEquals(
                "invalid_argument",
                deserialized.getMetadata().get(ToolResultBlock.METADATA_ERROR_KIND));
        assertEquals(
                "MEMORY.md", deserialized.getMetadata().get(ToolResultBlock.METADATA_ERROR_SCOPE));
        assertEquals(false, deserialized.getMetadata().get(ToolResultBlock.METADATA_RETRIABLE));
    }
}
