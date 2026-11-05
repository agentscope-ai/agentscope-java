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
package io.agentscope.extensions.a2ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelope;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelopeUtils;
import io.agentscope.extensions.a2ui.tool.A2uiTreeRenderTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Interoperability coverage: component trees submitted in the nested {@code root / children} shape,
 * catalogs read from files and URLs, and envelope versions written by other producers.
 */
class A2uiInteropTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final A2uiCatalog CATALOG =
            A2uiCatalog.load("a2ui/basic-catalog.json", "agentscope.io:a2ui/basic");

    private static A2uiRenderer renderer() {
        return new A2uiRenderer(
                A2uiConfig.defaults(),
                CATALOG,
                new io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry(
                        A2uiConfig.defaults(), () -> null),
                () -> null);
    }

    private static String present(Object components) {
        AtomicReference<String> sink = new AtomicReference<>();
        ToolResultBlock result =
                new A2uiTreeRenderTool(renderer(), sink::set)
                        .callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder().sessionId("s").build())
                                        .input(Map.of("components", components))
                                        .build())
                        .block();
        String text =
                result.getOutput().stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .reduce((a, b) -> a + b)
                        .orElse("");
        assertTrue(text.startsWith("{"), "submission rejected: " + text);
        return text;
    }

    private static List<Map<String, Object>> componentsOf(String envelopeJson) {
        try {
            return MAPPER.readValue(envelopeJson, A2uiEnvelope.class).components();
        } catch (Exception e) {
            throw new AssertionError("Not an envelope: " + envelopeJson, e);
        }
    }

    private static List<String> idsOf(List<Map<String, Object>> components) {
        return components.stream().map(c -> c.get("id")).map(String::valueOf).toList();
    }

    private static List<String> namesOf(List<Map<String, Object>> components) {
        return components.stream().map(c -> c.get("component")).map(String::valueOf).toList();
    }

    @Test
    void nestedTreeSubmissionIsFlattenedIntoTheEnvelopeShape() {
        Map<String, Object> tree =
                Map.of(
                        "root",
                        Map.of(
                                "id",
                                "card1",
                                "component",
                                "Card",
                                "title",
                                "Summary",
                                "children",
                                List.of(
                                        Map.of(
                                                "component",
                                                "Heading",
                                                "props",
                                                Map.of("text", "Hi", "level", 2)),
                                        Map.of("component", "Text", "text", "body"))));

        String envelope = present(tree);

        List<Map<String, Object>> components = componentsOf(envelope);
        assertEquals(List.of("card1", "c2", "c3"), idsOf(components));
        assertEquals(List.of("Card", "Heading", "Text"), namesOf(components));
        assertEquals(Map.of("title", "Summary"), components.get(0).get("props"));
        assertEquals(Map.of("text", "Hi", "level", 2), components.get(1).get("props"));
        assertEquals(Map.of("text", "body"), components.get(2).get("props"));
    }

    @Test
    void flatSubmissionWithInlinePropsGroupsThemIntoProps() {
        String envelope =
                present(
                        List.of(
                                Map.of(
                                        "id",
                                        "h1",
                                        "component",
                                        "Heading",
                                        "text",
                                        "Hi",
                                        "level",
                                        2)));

        assertEquals(Map.of("text", "Hi", "level", 2), componentsOf(envelope).get(0).get("props"));
    }

    @Test
    void presentToolSchemaPassesFrameworkArgumentValidationForBothShapes() {
        Map<String, Object> params = new A2uiTreeRenderTool(renderer(), null).getParameters();

        assertNull(
                ToolValidator.validateInput(
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "components",
                                                List.of(Map.of("id", "c", "component", "Text")))),
                        params));
        assertNull(
                ToolValidator.validateInput(
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "components",
                                                Map.of(
                                                        "root",
                                                        Map.of(
                                                                "component",
                                                                "Card",
                                                                "children",
                                                                List.of())))),
                        params));
    }

    @Test
    void catalogLoadsFromALocalFileAndKeepsItsOwnContents(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("teapot-catalog.json");
        Files.writeString(
                file,
                "{\"catalogId\":\"teapot:a2ui/custom\",\"version\":\"2.0\","
                        + "\"components\":{\"Heading\":{\"props\":{\"text\":{\"type\":\"string\"}},"
                        + "\"required\":[\"text\"]}}}");

        A2uiCatalog byPath = A2uiCatalog.load(file.toString(), null);
        assertEquals("teapot:a2ui/custom", byPath.catalogId());
        assertEquals("2.0", byPath.version());
        assertEquals(List.of("Heading"), List.copyOf(byPath.componentNames()));

        assertEquals(
                "teapot:a2ui/custom", A2uiCatalog.load(file.toUri().toString(), null).catalogId());
    }

    @Test
    void unreachableCatalogSourcesFallBackToTheBuiltInCatalog(@TempDir Path dir) {
        for (String spec :
                List.of(dir.resolve("gone.json").toString(), "http://127.0.0.1:1/a2ui.json")) {
            A2uiCatalog catalog = A2uiCatalog.load(spec, "agentscope.io:a2ui/basic");
            assertEquals(
                    CATALOG.componentNames(),
                    catalog.componentNames(),
                    "source " + spec + " must fall back, not fail the agent build");
        }
    }

    @Test
    void classpathCatalogAcceptsTheExplicitPrefix() {
        assertEquals(
                CATALOG.componentNames(),
                A2uiCatalog.load("classpath:a2ui/basic-catalog.json", null).componentNames());
        assertThrows(
                IllegalStateException.class,
                () -> A2uiCatalog.load("classpath:a2ui/does-not-exist.json", null));
    }

    @Test
    void extractEnvelopeAcceptsTheVersionSpellingsOtherProducersUse() {
        assertEquals(
                "s-1",
                A2uiEnvelopeUtils.extractEnvelope(
                                toolResult(
                                        "{\"protocolVersion\":\"1.0\","
                                            + "\"messageType\":\"createSurface\",\"surfaceId\":\"s-1\","
                                            + "\"catalogId\":\"c\",\"components\":[]}"))
                        .map(A2uiEnvelope::surfaceId)
                        .orElse(null));
        Optional<A2uiEnvelope> prefixed =
                A2uiEnvelopeUtils.extractEnvelope(
                        toolResult(
                                "{\"protocolVersion\":\"v1.0\","
                                    + "\"messageType\":\"updateComponents\",\"surfaceId\":\"s-2\","
                                    + "\"catalogId\":\"c\",\"components\":[]}"));
        assertTrue(prefixed.isPresent());
        assertEquals("s-2", prefixed.get().surfaceId());

        assertTrue(
                A2uiEnvelopeUtils.extractEnvelope(
                                toolResult(
                                        "{\"protocolVersion\":\"v2.0\","
                                                + "\"messageType\":\"createSurface\","
                                                + "\"surfaceId\":\"s-3\",\"components\":[]}"))
                        .isEmpty());
    }

    private static Msg toolResult(String json) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.of(
                                "t1", "a2ui_render", TextBlock.builder().text(json).build()))
                .build();
    }
}
