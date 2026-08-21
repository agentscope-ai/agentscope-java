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
package io.agentscope.extensions.model.anthropic.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.UserLocation;
import com.anthropic.models.messages.WebSearchTool20250305;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for AnthropicServerTool. */
class AnthropicServerToolTest {

    @Test
    void testWebSearchWithAllSupportedParams() {
        AnthropicServerTool tool =
                AnthropicServerTool.webSearch()
                        .param("max_uses", 5)
                        .param("allowed_domains", List.of("example.com", "docs.example.com"))
                        .param(
                                "user_location",
                                Map.of(
                                        "type", "approximate",
                                        "city", "Hangzhou",
                                        "region", "Zhejiang",
                                        "country", "CN",
                                        "timezone", "Asia/Shanghai"))
                        .build();

        assertEquals("web_search_20250305", tool.getType());

        ToolUnion union = tool.toToolUnion();
        assertTrue(union.isWebSearchTool20250305());
        WebSearchTool20250305 sdkTool = union.asWebSearchTool20250305();
        assertEquals(5L, sdkTool.maxUses().orElseThrow());
        assertEquals(
                List.of("example.com", "docs.example.com"), sdkTool.allowedDomains().orElseThrow());
        UserLocation location = sdkTool.userLocation().orElseThrow();
        assertEquals("Hangzhou", location.city().orElseThrow());
        assertEquals("Zhejiang", location.region().orElseThrow());
        assertEquals("CN", location.country().orElseThrow());
        assertEquals("Asia/Shanghai", location.timezone().orElseThrow());
    }

    @Test
    void testWebSearchWithBlockedDomains() {
        AnthropicServerTool tool =
                AnthropicServerTool.webSearch()
                        .param("blocked_domains", List.of("spam.example"))
                        .build();

        WebSearchTool20250305 sdkTool = tool.toToolUnion().asWebSearchTool20250305();
        assertEquals(List.of("spam.example"), sdkTool.blockedDomains().orElseThrow());
        assertTrue(sdkTool.maxUses().isEmpty());
    }

    @Test
    void testExplicitTypeEqualsWebSearchFactory() {
        AnthropicServerTool tool =
                AnthropicServerTool.builder().type("web_search_20250305").build();

        assertEquals(AnthropicServerTool.TYPE_WEB_SEARCH_20250305, tool.getType());
        assertTrue(tool.toToolUnion().isWebSearchTool20250305());
    }

    @Test
    void testWebFetchTool() throws Exception {
        AnthropicServerTool tool =
                AnthropicServerTool.webFetch()
                        .param("max_uses", 3)
                        .param("allowed_domains", List.of("example.com"))
                        .build();

        ToolUnion union = tool.toToolUnion();
        assertTrue(union.isWebFetchTool20250910());

        // The serialized request JSON carries the versioned type and the tool name
        String json = ObjectMappers.jsonMapper().writeValueAsString(union);
        assertTrue(json.contains("\"type\":\"web_fetch_20250910\""));
        assertTrue(json.contains("\"name\":\"web_fetch\""));
        assertTrue(json.contains("\"max_uses\":3"));
    }

    @Test
    void testCodeExecutionTool() throws Exception {
        AnthropicServerTool tool = AnthropicServerTool.codeExecution().build();

        ToolUnion union = tool.toToolUnion();
        assertTrue(union.isCodeExecutionTool20250825());

        String json = ObjectMappers.jsonMapper().writeValueAsString(union);
        assertTrue(json.contains("\"type\":\"code_execution_20250825\""));
        assertTrue(json.contains("\"name\":\"code_execution\""));
    }

    @Test
    void testToolSearchTool() {
        AnthropicServerTool tool =
                AnthropicServerTool.builder().type("tool_search_tool_bm25_20251119").build();

        assertTrue(tool.toToolUnion().isSearchToolBm25_20251119());
    }

    @Test
    void testUnknownTypeThrowsAtBuild() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                AnthropicServerTool.builder()
                                        .type("quantum_search_20990101")
                                        .build());
        assertTrue(ex.getMessage().contains("quantum_search_20990101"));
    }

    @Test
    void testMissingTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> AnthropicServerTool.builder().build());
    }

    @Test
    void testUnknownParamPassesThrough() throws Exception {
        // Unknown parameters are forwarded verbatim and validated server-side, so newly added
        // API fields work without an SDK upgrade
        AnthropicServerTool tool = AnthropicServerTool.webSearch().param("future_param", 1).build();

        String json = ObjectMappers.jsonMapper().writeValueAsString(tool.toToolUnion());
        assertTrue(json.contains("\"future_param\":1"));
    }

    @Test
    void testInvalidParamValueTypeThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AnthropicServerTool.webSearch().param("max_uses", "five").build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnthropicServerTool.webSearch()
                                .param("allowed_domains", "example.com")
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () -> AnthropicServerTool.webSearch().param("user_location", "Hangzhou").build());
    }
}
