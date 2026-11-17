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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            A2uiCatalog.load(A2uiCatalog.BASIC_CATALOG_RESOURCE, null);

    private static A2uiRenderer renderer(A2uiCatalog catalog) {
        return new A2uiRenderer(
                A2uiConfig.defaults(),
                catalog,
                new io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry(
                        A2uiConfig.defaults(), () -> null),
                () -> null);
    }

    private static String submit(A2uiCatalog catalog, Object components) {
        AtomicReference<String> sink = new AtomicReference<>();
        ToolResultBlock result =
                new A2uiTreeRenderTool(renderer(catalog), sink::set)
                        .callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder().sessionId("s").build())
                                        .input(Map.of("components", components))
                                        .build())
                        .block();
        return result.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .reduce((a, b) -> a + b)
                .orElse("");
    }

    private static String present(Object components) {
        return present(CATALOG, components);
    }

    private static String present(A2uiCatalog catalog, Object components) {
        String text = submit(catalog, components);
        assertTrue(text.startsWith("{"), "submission rejected: " + text);
        return text;
    }

    private static String surfaceCatalogId(String envelopeJson) throws Exception {
        return MAPPER.readTree(envelopeJson)
                .path("messages")
                .get(0)
                .path("createSurface")
                .path("catalogId")
                .asText(null);
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
                                "col1",
                                "component",
                                "Column",
                                "align",
                                "stretch",
                                "children",
                                List.of(
                                        Map.of(
                                                "component",
                                                "Text",
                                                "props",
                                                Map.of("text", "## Hi")),
                                        Map.of("component", "Text", "text", "body"))));

        String envelope = present(tree);

        List<Map<String, Object>> components = componentsOf(envelope);
        assertEquals(List.of("root", "c1", "c2"), idsOf(components));
        assertEquals(List.of("Column", "Text", "Text"), namesOf(components));
        assertEquals(List.of("c1", "c2"), components.get(0).get("children"));
        assertEquals("stretch", components.get(0).get("align"));
        assertEquals("## Hi", components.get(1).get("text"));
        assertEquals("body", components.get(2).get("text"));
        for (Map<String, Object> component : components) {
            assertNull(component.get("props"), "v1.0 properties sit inline: " + component);
        }
    }

    @Test
    void flatSubmissionKeepsPropertiesInlineAndPromotesTheRoot() {
        String envelope =
                present(List.of(Map.of("id", "h1", "component", "Text", "text", "## Hi")));

        List<Map<String, Object>> components = componentsOf(envelope);
        assertEquals(List.of("root"), idsOf(components));
        assertEquals("## Hi", components.get(0).get("text"));
        assertNull(components.get(0).get("props"));
    }

    /**
     * 回归护栏：`components` 必须留 array 类型。v1.14 一度把它放开成无类型，线上七次提交全被判成
     * "must be a non-empty array" —— provider 对无类型属性把值当字符串交进来，归一化根本收不到列表。
     */
    @Test
    void presentToolSchemaKeepsComponentsTypedAndAcceptsNestedChildren() {
        Map<String, Object> params =
                new A2uiTreeRenderTool(renderer(CATALOG), null).getParameters();

        Map<String, Object> flat =
                Map.of(
                        "components",
                        List.of(Map.of("component", "Text", "props", Map.of("text", "Hi"))));
        Map<String, Object> nested =
                Map.of(
                        "components",
                        List.of(
                                Map.of(
                                        "component",
                                        "Column",
                                        "children",
                                        List.of(
                                                Map.of(
                                                        "component",
                                                        "Text",
                                                        "props",
                                                        Map.of("text", "body"))))));
        Map<String, Object> template =
                Map.of(
                        "components",
                        List.of(
                                Map.of(
                                        "component",
                                        "List",
                                        "children",
                                        Map.of("path", "/items", "componentId", "tpl"))));

        assertNull(ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(flat), params));
        assertNull(ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(nested), params));
        assertNull(ToolValidator.validateInput(JsonUtils.getJsonCodec().toJson(template), params));
        // 顶层对象形态不再是从归一化进来的入口，会被框架的 schema 门挡下
        assertNotNull(
                ToolValidator.validateInput(
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "components",
                                                Map.of("root", Map.of("component", "Card")))),
                        params));
    }

    @Test
    void stringifiedTreeSubmissionIsParsedBeforeValidation() {
        String envelope = present("[{\"component\":\"Text\",\"props\":{\"text\":\"Hi\"}}]");

        assertEquals(List.of("root"), idsOf(componentsOf(envelope)));
        assertEquals(List.of("Text"), namesOf(componentsOf(envelope)));
    }

    /** A custom catalog document: its own components, no copy of the basic schemas. */
    private static Path writeCustomCatalog(Path dir, String components) throws Exception {
        Path file = dir.resolve("teapot-catalog.json");
        Files.writeString(
                file,
                "{\"catalogId\":\"teamer:a2ui/teapot\",\"protocolVersion\":\"1.0\","
                        + "\"instructions\":\"Use Badge for status.\",\"components\":"
                        + components
                        + "}");
        return file;
    }

    private static final String BADGE =
            "{\"Badge\":{\"type\":\"object\",\"properties\":{"
                    + "\"component\":{\"const\":\"Badge\"},"
                    + "\"text\":{\"$ref\":\"common_types.json#/$defs/DynamicString\"}},"
                    + "\"required\":[\"component\",\"text\"]}}";

    @Test
    void customCatalogLayersOverTheBasicCatalogAndKeepsOwnership(@TempDir Path dir)
            throws Exception {
        Path file = writeCustomCatalog(dir, BADGE);

        A2uiCatalog catalog = A2uiCatalog.load(file.toString(), null);

        assertEquals("teamer:a2ui/teapot", catalog.catalogId());
        assertEquals("1.0", catalog.protocolVersion());
        assertEquals(19, catalog.componentNames().size());
        assertTrue(catalog.componentNames().containsAll(List.of("Badge", "Text", "ChoicePicker")));
        assertEquals("teamer:a2ui/teapot", catalog.catalogIdOf("Badge"));
        assertEquals(A2uiCatalog.BASIC_CATALOG_ID, catalog.catalogIdOf("Text"));
        assertNull(catalog.catalogIdOf("NoSuchComponent"));
        assertEquals(14, catalog.functionNames().size());
        assertTrue(catalog.guideText().contains("### Badge"));
        assertTrue(catalog.guideText().contains("### TextField"));
        assertTrue(catalog.instructions().startsWith("Use Badge for status."));
        assertTrue(catalog.instructions().contains("formatString"));

        assertEquals(
                "teamer:a2ui/teapot", A2uiCatalog.load(file.toUri().toString(), null).catalogId());
    }

    @Test
    void aCustomEntryWinsOverTheBasicComponentOfTheSameName(@TempDir Path dir) throws Exception {
        A2uiCatalog catalog =
                A2uiCatalog.load(
                        writeCustomCatalog(
                                        dir,
                                        "{\"Text\":{\"type\":\"object\",\"properties\":{"
                                                + "\"component\":{\"const\":\"Text\"},"
                                                + "\"tone\":{\"type\":\"string\"}},"
                                                + "\"required\":[\"component\",\"tone\"]}}")
                                .toString(),
                        null);

        assertEquals(18, catalog.componentNames().size());
        assertEquals("teamer:a2ui/teapot", catalog.catalogIdOf("Text"));
        assertEquals(
                A2uiCatalog.BASIC_CATALOG_ID, catalog.catalogIdOf("Column"), "others stay basic");
        assertTrue(
                submit(catalog, List.of(Map.of("id", "root", "component", "Text", "text", "Hi")))
                        .contains("tone"),
                "the custom Text schema must be the one in force");
    }

    @Test
    void mixedSurfaceStampsOnlyTheComponentsOwnedByAnotherCatalog(@TempDir Path dir)
            throws Exception {
        A2uiCatalog catalog = A2uiCatalog.load(writeCustomCatalog(dir, BADGE).toString(), null);
        Map<String, Object> tree =
                Map.of(
                        "id",
                        "root",
                        "component",
                        "Column",
                        "children",
                        List.of(
                                Map.of("id", "t", "component", "Text", "text", "Hi"),
                                Map.of(
                                        "id",
                                        "b",
                                        "component",
                                        "Badge",
                                        "text",
                                        Map.of(
                                                "@call",
                                                "formatString",
                                                "args",
                                                Map.of("value", "Status: ${/status}")))));

        String envelope = present(catalog, tree);

        assertEquals("teamer:a2ui/teapot", surfaceCatalogId(envelope));
        List<Map<String, Object>> components = componentsOf(envelope);
        assertEquals(List.of("root", "t", "b"), idsOf(components));
        assertEquals(A2uiCatalog.BASIC_CATALOG_ID, components.get(0).get("catalogId"));
        assertEquals(A2uiCatalog.BASIC_CATALOG_ID, components.get(1).get("catalogId"));
        assertNull(components.get(2).get("catalogId"), "the surface default already owns Badge");
        // The whole envelope must pass the official wrapper schema: its Component/FunctionCall
        // unions resolve through the merged catalog document.
        assertEquals(
                List.of(),
                catalog.schemas().messages().validate(MAPPER.readTree(envelope)).stream()
                        .map(Object::toString)
                        .toList());
    }

    @Test
    void theBasicCatalogStampsNothing() throws Exception {
        String envelope = present(List.of(Map.of("id", "root", "component", "Text", "text", "Hi")));

        assertEquals(A2uiCatalog.BASIC_CATALOG_ID, surfaceCatalogId(envelope));
        assertNull(componentsOf(envelope).get(0).get("catalogId"));
    }

    @Test
    void aCustomComponentIsRejectedAgainstItsOwnSchema(@TempDir Path dir) throws Exception {
        A2uiCatalog catalog = A2uiCatalog.load(writeCustomCatalog(dir, BADGE).toString(), null);

        String rejection =
                submit(catalog, List.of(Map.of("id", "root", "component", "Badge", "text", 42)));

        assertTrue(rejection.startsWith("Error"), rejection);
        assertTrue(rejection.contains("Badge"), rejection);
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
                A2uiCatalog.load("classpath:" + A2uiCatalog.BASIC_CATALOG_RESOURCE, null)
                        .componentNames());
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
                                        "{\"messages\":[{\"version\":\"1.0\","
                                                + "\"createSurface\":{\"surfaceId\":\"s-1\","
                                                + "\"catalogId\":\"c\",\"components\":[]}}]}"))
                        .map(A2uiEnvelope::surfaceId)
                        .orElse(null));
        Optional<A2uiEnvelope> prefixed =
                A2uiEnvelopeUtils.extractEnvelope(
                        toolResult(
                                "{\"messages\":[{\"version\":\"v1.0\","
                                        + "\"updateComponents\":{\"surfaceId\":\"s-2\","
                                        + "\"components\":[]}}]}"));
        assertTrue(prefixed.isPresent());
        assertEquals("s-2", prefixed.get().surfaceId());

        assertTrue(
                A2uiEnvelopeUtils.extractEnvelope(
                                toolResult(
                                        "{\"messages\":[{\"version\":\"v2.0\","
                                                + "\"createSurface\":{\"surfaceId\":\"s-3\","
                                                + "\"components\":[]}}]}"))
                        .isEmpty());
    }

    /** Envelopes written before the v1.0 alignment are not protocol messages any more. */
    @Test
    void extractEnvelopeIgnoresTheLegacyTopLevelShape() {
        assertTrue(
                A2uiEnvelopeUtils.extractEnvelope(
                                toolResult(
                                        "{\"protocolVersion\":\"1.0\","
                                                + "\"messageType\":\"createSurface\","
                                                + "\"surfaceId\":\"s-old\",\"components\":[]}"))
                        .isEmpty());
    }

    @Test
    void noteTravelsInTheRootExtensionsWithoutMutatingTheOriginal() {
        A2uiEnvelope original =
                A2uiEnvelope.createSurface(
                        "s-note",
                        CATALOG.catalogId(),
                        List.of(
                                Map.of("id", "root", "component", "Text", "text", "Hi"),
                                Map.of("id", "other", "component", "Text", "text", "x")),
                        null);

        A2uiEnvelope annotated = original.withNote("Already delivered; stop.");

        assertEquals("Already delivered; stop.", annotated.note());
        assertNull(original.note(), "withNote must copy, not mutate");
        assertEquals(
                original.components().get(1),
                annotated.components().get(1),
                "siblings are shared, only the root is replaced");
        assertNull(original.withNote("  ").note());
    }

    @Test
    void guideDocumentsTheProtocolMixinsOnceAndNamesTheCheckableComponents() {
        String guide = CATALOG.guideText();

        assertTrue(guide.contains("## Common component properties"), guide);
        for (String mixin : List.of("`catalogId`", "`accessibility`", "`metadata`")) {
            assertTrue(guide.contains(mixin), mixin + " must reach the model: " + guide);
        }
        assertTrue(guide.contains("aria-live") || guide.contains("polite"), guide);
        assertTrue(guide.contains("`@index`"), guide);
        assertTrue(guide.contains("Only these components also accept `checks`:"), guide);
        assertTrue(guide.contains("TextField, CheckBox, ChoicePicker"), guide);
        assertEquals(
                1,
                countOf(guide, "\"condition\":\"binding | call\""),
                "the checks shape is documented once, not on every Checkable component");
        assertEquals(0, countOf(guide.substring(guide.indexOf("### ")), "- `checks`"));
    }

    @Test
    void textFieldRequiresAValueToBindTo() {
        String guide = CATALOG.guideText();
        int start = guide.indexOf("### TextField");
        String section = guide.substring(start, guide.indexOf("### ", start + 4));
        assertTrue(section.contains("`value` (string | binding | call, required)"), section);

        String rejection =
                submit(
                        CATALOG,
                        List.of(Map.of("id", "root", "component", "TextField", "label", "Email")));
        assertTrue(rejection.startsWith("Error"), rejection);
        assertTrue(rejection.contains("value"), rejection);

        assertNotNull(
                present(
                        List.of(
                                Map.of(
                                        "id",
                                        "root",
                                        "component",
                                        "TextField",
                                        "label",
                                        "Email",
                                        "value",
                                        Map.of("@path", "/email")))));
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
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
