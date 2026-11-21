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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelopeValidator;
import io.agentscope.extensions.a2ui.envelope.A2uiValidationException;
import io.agentscope.extensions.a2ui.state.A2uiRunState;
import io.agentscope.extensions.a2ui.tool.A2uiRenderTool;
import io.agentscope.extensions.a2ui.tool.A2uiTreeRenderTool;
import io.agentscope.extensions.a2ui.tool.AskUserQuestionTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Unit coverage for the A2UI pipeline: catalog, validator, surfaces, tools, middleware. */
class A2uiPipelineTest {

    private static final A2uiCatalog CATALOG =
            A2uiCatalog.load(A2uiCatalog.BASIC_CATALOG_RESOURCE, null);

    private static A2uiRenderer renderer(A2uiConfig config) {
        return new A2uiRenderer(
                config,
                CATALOG,
                new io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry(config, () -> null),
                () -> null);
    }

    private static A2uiRenderer renderer(A2uiConfig config, ChatModelBase renderModel) {
        return new A2uiRenderer(
                config,
                CATALOG,
                new io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry(config, () -> null),
                () -> renderModel);
    }

    /** Stream that never emits: exercises the render wall-clock budget. */
    private static final class NeverModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "never";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.never();
        }
    }

    /** Replays one scripted {@link ChatResponse} per model call; empty text when exhausted. */
    private static final class ScriptedModel extends ChatModelBase {
        private final List<ChatResponse> script;
        private final AtomicInteger idx = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        volatile List<String> toolNames = List.of();

        ScriptedModel(List<ChatResponse> script) {
            this.script = script;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            toolNames = tools.stream().map(ToolSchema::getName).toList();
            int i = idx.getAndIncrement();
            return Flux.just(i < script.size() ? script.get(i) : textResponse(""));
        }
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse toolUse(String id, String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(id)
                                        .name(name)
                                        .input(input)
                                        .content(
                                                io.agentscope.core.util.JsonUtils.getJsonCodec()
                                                        .toJson(input))
                                        .build()))
                .build();
    }

    /** One v1.0 component: properties sit inline beside {@code component}, never under props. */
    private static Map<String, Object> comp(
            String id, String component, Map<String, Object> props) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("component", component);
        out.putAll(props);
        return out;
    }

    private static List<Map<String, Object>> textSurface(String text) {
        return List.of(comp("root", "Text", Map.of("text", text)));
    }

    private static String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    @Test
    void builtInCatalogLoadsAllComponentsAndGuideText() {
        assertTrue(CATALOG.componentNames().contains("Text"));
        assertTrue(CATALOG.componentNames().contains("ChoicePicker"));
        assertEquals(18, CATALOG.componentNames().size());
        assertEquals(14, CATALOG.functionNames().size());
        String guide = CATALOG.guideText();
        assertTrue(guide.contains("### Text"));
        assertTrue(guide.contains(CATALOG.catalogId()));
        assertTrue(guide.contains("id: \"root\""), "the guide must state the root rule");
        // Channel-agnostic: the prompt/tool description names the submit tool, the guide does not.
        assertFalse(guide.contains("a2ui_"));
    }

    /**
     * 回归护栏：官方 catalog 的属性类型几乎都是 common_types 里的 $ref 联合。裸 {@code #/$defs/…} 指针一度
     * 被解析到 catalog 文档上，Card.child 标成 any；联合又一律标成 "string | binding"，于是 Slider.value
     * 看起来该传字符串、Button.action 看起来不是对象，shape 行还在结构结束前就被截断。
     */
    @Test
    void guideLabelsProtocolTypesAndKeepsWholeShapes() {
        String guide = CATALOG.guideText();
        assertTrue(guide.contains("`child` (string, required)"), "Child resolves to ComponentId");
        assertTrue(guide.contains("`action` ({event} | {functionCall}, required)"));
        assertTrue(guide.contains("`children` (string[] | {componentId, path}, required)"));
        assertTrue(guide.contains("`value` (number | binding | call, required)"));
        assertTrue(guide.contains("`value` (boolean | binding | call, required)"));
        assertTrue(guide.contains("`value` (string[] | binding | call, required)"));
        assertFalse(guide.contains("(string | binding)"), "a dynamic union is not a plain string");
        assertFalse(guide.contains("(any)"), "every referenced type resolves");
        assertFalse(guide.contains("\"$ref\""), "shapes are resolved, not left as pointers");
        assertTrue(
                guide.contains("\"required\":[\"functionCall\"]"),
                "the action shape must survive the clip limit whole");
    }

    @Test
    void missingCatalogResourceFailsFast() {
        assertThrows(
                IllegalStateException.class,
                () -> A2uiCatalog.load("a2ui/does-not-exist.json", null));
    }

    @Test
    void validatorAcceptsWellFormedComponents() {
        assertTrue(
                A2uiEnvelopeValidator.validate(
                                List.of(
                                        comp(
                                                "root",
                                                "Column",
                                                Map.of("children", List.of("h1", "t1"))),
                                        comp("h1", "Text", Map.of("text", "## Hi")),
                                        comp("t1", "Text", Map.of("text", "body"))),
                                CATALOG,
                                50)
                        .isEmpty());
    }

    @Test
    void validatorAcceptsDataBindingsAndListTemplates() {
        assertTrue(
                A2uiEnvelopeValidator.validate(
                                List.of(
                                        comp(
                                                "root",
                                                "List",
                                                Map.of(
                                                        "children",
                                                        Map.of(
                                                                "path", "/items",
                                                                "componentId", "tpl"))),
                                        comp(
                                                "tpl",
                                                "Text",
                                                Map.of("text", Map.of("@path", "/label")))),
                                CATALOG,
                                50)
                        .isEmpty());
    }

    @Test
    void validatorRejectsUnknownComponent() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("root", "Spaceship", Map.of())), CATALOG, 50);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("unknown component \"Spaceship\""));
    }

    @Test
    void validatorRejectsMissingRequiredProps() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("root", "ChoicePicker", Map.of("label", "q"))), CATALOG, 50);
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("required property 'options' not found")),
                errors.toString());
    }

    @Test
    void validatorRejectsWrongPropTypeAndEnumViolation() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(
                                comp("root", "Column", Map.of("children", List.of("t1", "t2"))),
                                comp("t1", "Text", Map.of("text", 42)),
                                comp("t2", "Text", Map.of("text", "t", "variant", "purple"))),
                        CATALOG,
                        50);
        assertTrue(errors.stream().anyMatch(e -> e.contains("at /text")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("enumeration")), errors.toString());
    }

    /** A declared prop that fails its own check must not also be reported as unevaluated. */
    @Test
    void validatorReportsTheSpecificFailureNotTheClosedSchemaCascade() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("root", "Text", Map.of("text", "t", "variant", "purple"))),
                        CATALOG,
                        50);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("enumeration"), errors.get(0));
    }

    /** One mistyped prop must not arrive as every union branch's complaint about it. */
    @Test
    void validatorCollapsesUnionBranchNoiseIntoOneLine() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("root", "Text", Map.of("text", 42))), CATALOG, 50);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("string expected"), errors.get(0));
    }

    @Test
    void validatorRejectsAPropertyTheCatalogDoesNotDeclare() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("root", "Column", Map.of("children", List.of(), "gap", 8))),
                        CATALOG,
                        50);
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("'gap' is not evaluated")),
                errors.toString());
    }

    @Test
    void validatorRejectsDuplicateIdsAndEmptyOrNullList() {
        assertTrue(A2uiEnvelopeValidator.validate(List.of(), CATALOG, 50).size() == 1);
        assertTrue(A2uiEnvelopeValidator.validate(null, CATALOG, 50).size() == 1);
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(
                                comp("root", "Text", Map.of("text", "a")),
                                comp("root", "Text", Map.of("text", "b"))),
                        CATALOG,
                        50);
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate component id")));
    }

    @Test
    void validatorEnforcesMaxComponents() {
        List<Map<String, Object>> many =
                java.util.stream.IntStream.range(0, 5)
                        .mapToObj(
                                i ->
                                        i == 0
                                                ? comp(
                                                        "root",
                                                        "Column",
                                                        Map.of(
                                                                "children",
                                                                List.of("c1", "c2", "c3", "c4")))
                                                : comp("c" + i, "Text", Map.of("text", "x")))
                        .toList();
        List<String> errors = A2uiEnvelopeValidator.validate(many, CATALOG, 3);
        assertTrue(errors.stream().anyMatch(e -> e.contains("at most 3")), errors.toString());
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("List template")),
                "the budget error must point at the way out, not just the ceiling");
    }

    /** Graph rules the JSON Schemas cannot express; a strict renderer rejects the same shapes. */
    @Test
    void validatorRejectsMissingRootDanglingReferenceAndOrphan() {
        List<String> missingRoot =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("c1", "Text", Map.of("text", "a"))), CATALOG, 50);
        assertTrue(
                missingRoot.stream().anyMatch(e -> e.contains("no component has id \"root\"")),
                missingRoot.toString());

        List<String> dangling =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("root", "Card", Map.of("child", "ghost"))), CATALOG, 50);
        assertTrue(
                dangling.stream().anyMatch(e -> e.contains("references \"ghost\"")),
                dangling.toString());

        List<String> orphan =
                A2uiEnvelopeValidator.validate(
                        List.of(
                                comp("root", "Text", Map.of("text", "a")),
                                comp("stray", "Text", Map.of("text", "b"))),
                        CATALOG,
                        50);
        assertTrue(
                orphan.stream().anyMatch(e -> e.contains("unreachable from \"root\"")),
                orphan.toString());
    }

    @Test
    void validatorFollowsReferencesNestedInsideProps() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(
                                comp(
                                        "root",
                                        "Tabs",
                                        Map.of(
                                                "tabs",
                                                List.of(
                                                        Map.of(
                                                                "label", "One",
                                                                "child", "panel"))))),
                        CATALOG,
                        50);
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("references \"panel\"")),
                "tabs[].child is an id reference too: " + errors);
    }

    @Test
    void validatorCapsTheReportedProblems() {
        List<Object> children = new ArrayList<>();
        List<Map<String, Object>> many = new ArrayList<>();
        many.add(comp("root", "Column", Map.of("children", children)));
        for (int i = 1; i <= 24; i++) {
            children.add("c" + i);
            many.add(comp("c" + i, "Text", Map.of("text", "x", "bogus" + i, 1)));
        }
        List<String> errors = A2uiEnvelopeValidator.validate(many, CATALOG, 50);
        assertEquals(21, errors.size(), errors.toString());
        assertTrue(errors.get(20).contains("further problems omitted"), errors.get(20));
    }

    @Test
    void firstRenderCreatesSurfaceAndLaterRendersUpdateIt() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-a").build();
        A2uiRenderer renderer = renderer(A2uiConfig.defaults());
        String first = renderer.renderEnvelope(rc, textSurface("a"));
        String second = renderer.renderEnvelope(rc, textSurface("b"));

        assertTrue(first.contains("\"version\":\"1.0\""), first);
        assertTrue(first.contains("\"createSurface\":{"), first);
        assertTrue(first.contains("\"catalogId\":\"" + CATALOG.catalogId() + "\""), first);
        assertTrue(second.contains("\"updateComponents\":{"), second);

        String surfaceId = first.replaceAll(".*\"surfaceId\":\"(s-[0-9a-f]+)\".*", "$1");
        assertTrue(surfaceId.matches("s-[0-9a-f]{8}"), surfaceId);
        assertTrue(second.contains(surfaceId), "same surface reused within the run");
    }

    @Test
    void dataModelRidesInCreateSurfaceAndInItsOwnUpdateMessage() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-dm").build();
        A2uiRenderer renderer = renderer(A2uiConfig.defaults());
        List<Map<String, Object>> components =
                List.of(comp("root", "Text", Map.of("text", Map.of("@path", "/greeting"))));

        String first = renderer.renderEnvelope(rc, components, Map.of("greeting", "Hi"));
        String second = renderer.renderEnvelope(rc, components, Map.of("greeting", "Hello"));

        assertTrue(first.contains("\"dataModel\":{\"greeting\":\"Hi\"}"), first);
        assertFalse(first.contains("updateDataModel"), first);
        assertTrue(second.contains("\"updateComponents\":{"), second);
        assertTrue(
                second.contains("\"updateDataModel\":{\"surfaceId\":"),
                "updateComponents carries no data model of its own: " + second);
        assertTrue(second.contains("\"value\":{\"greeting\":\"Hello\"}"), second);
    }

    @Test
    void treeRenderToolValidatesAndCapturesEnvelope() {
        AtomicReference<String> sink = new AtomicReference<>();
        A2uiTreeRenderTool tool =
                new A2uiTreeRenderTool(renderer(A2uiConfig.defaults()), sink::set);
        assertEquals("a2ui_present", tool.getName());
        RuntimeContext rc = RuntimeContext.builder().sessionId("s").build();

        ToolResultBlock invalid =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(rc)
                                        .input(
                                                Map.of(
                                                        "components",
                                                        List.of(comp("root", "Nope", Map.of()))))
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, invalid.getState());
        assertTrue(textOf(invalid).contains("unknown component"));
        assertTrue(sink.get() == null, "rejected submissions must not reach the sink");

        ToolResultBlock valid =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(rc)
                                        .input(Map.of("components", textSurface("Hi")))
                                        .build())
                        .block();
        assertEquals(ToolResultState.RUNNING, valid.getState());
        assertEquals(textOf(valid), sink.get());
        assertTrue(sink.get().contains("\"createSurface\":{"));
    }

    @Test
    void renderToolRunsChildAgentInSubmitAndDoneTurns() {
        ScriptedModel child =
                new ScriptedModel(
                        List.of(
                                toolUse(
                                        "t1",
                                        "a2ui_present",
                                        Map.of("components", textSurface("## Hi"))),
                                textResponse("done")));
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults(), child));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder().sessionId("s").build())
                                        .input(Map.of("description", "Show a greeting"))
                                        .build())
                        .block();
        assertEquals(ToolResultState.RUNNING, result.getState(), textOf(result));
        assertTrue(textOf(result).contains("\"createSurface\":{"));
        assertEquals(2, child.calls.get());
        assertEquals(List.of("a2ui_present"), child.toolNames);
    }

    @Test
    void repeatedIntentInSameRunReplaysTheDeliveredSurfaceWithoutRenderingAgain() {
        ScriptedModel child =
                new ScriptedModel(
                        List.of(
                                toolUse(
                                        "t1",
                                        "a2ui_present",
                                        Map.of("components", textSurface("## Hi"))),
                                textResponse("done")));
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults(), child));
        RuntimeContext rc = RuntimeContext.builder().sessionId("run-once").build();
        A2uiRunState.install(rc);

        String first =
                textOf(
                        tool.callAsync(
                                        ToolCallParam.builder()
                                                .runtimeContext(rc)
                                                .input(Map.of("description", "Show a greeting"))
                                                .build())
                                .block());
        String second =
                textOf(
                        tool.callAsync(
                                        ToolCallParam.builder()
                                                .runtimeContext(rc)
                                                .input(Map.of("description", "Show a greeting"))
                                                .build())
                                .block());

        assertTrue(first.contains("\"createSurface\":{"), first);
        assertTrue(second.contains("\"createSurface\":{"), second);
        assertTrue(second.contains("force=true"), second);
        assertTrue(second.contains("NO-OP"), second);
        assertTrue(second.contains("Do NOT call a2ui_render again"), second);
        assertEquals(
                second.replaceAll(".*\"surfaceId\":\"(s-[0-9a-f]+)\".*", "$1"),
                first.replaceAll(".*\"surfaceId\":\"(s-[0-9a-f]+)\".*", "$1"));
        assertEquals(2, child.calls.get(), "repeated intent must not render a second time");
    }

    @Test
    void forcedIntentInSameRunRendersAgain() {
        ScriptedModel child =
                new ScriptedModel(
                        List.of(
                                toolUse(
                                        "t1",
                                        "a2ui_present",
                                        Map.of("components", textSurface("## Hi"))),
                                textResponse("done"),
                                toolUse(
                                        "t2",
                                        "a2ui_present",
                                        Map.of("components", textSurface("again"))),
                                textResponse("done")));
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults(), child));
        RuntimeContext rc = RuntimeContext.builder().sessionId("run-forced").build();
        A2uiRunState.install(rc);
        tool.callAsync(
                        ToolCallParam.builder()
                                .runtimeContext(rc)
                                .input(Map.of("description", "Show a greeting"))
                                .build())
                .block();

        String forced =
                textOf(
                        tool.callAsync(
                                        ToolCallParam.builder()
                                                .runtimeContext(rc)
                                                .input(
                                                        Map.of(
                                                                "description",
                                                                "Show a greeting",
                                                                "force",
                                                                true))
                                                .build())
                                .block());

        assertEquals(4, child.calls.get(), "force=true must render again");
        assertTrue(forced.contains("\"updateComponents\":{"), forced);
    }

    @Test
    void runSurfaceSurvivesDerivedContextCopies() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("run-copies").build();
        A2uiRunState.install(rc);
        A2uiRenderer renderer = renderer(A2uiConfig.defaults(), new ScriptedModel(List.of()));

        String first =
                renderer.renderEnvelope(RuntimeContext.builder(rc).build(), textSurface("a"));
        String second =
                renderer.renderEnvelope(RuntimeContext.builder(rc).build(), textSurface("b"));

        assertTrue(first.contains("\"createSurface\":{"), first);
        assertTrue(
                second.contains("\"updateComponents\":{"),
                "the framework hands each tool call a derived copy of the context; the run's"
                        + " surface must survive it");
    }

    @Test
    void childAgentSelfCorrectsValidationErrorsInsideItsLoop() {
        ScriptedModel child =
                new ScriptedModel(
                        List.of(
                                toolUse(
                                        "t1",
                                        "a2ui_present",
                                        Map.of(
                                                "components",
                                                List.of(comp("root", "Nope", Map.of())))),
                                toolUse(
                                        "t2",
                                        "a2ui_present",
                                        Map.of("components", textSurface("ok"))),
                                textResponse("done")));
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults(), child));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder().sessionId("s").build())
                                        .input(Map.of("description", "Anything"))
                                        .build())
                        .block();
        assertEquals(ToolResultState.RUNNING, result.getState(), textOf(result));
        assertTrue(textOf(result).contains("\"createSurface\":{"));
        assertEquals(3, child.calls.get());
    }

    @Test
    void renderPromptCarriesComponentBudgetCeiling() {
        A2uiRenderManager defaults =
                new A2uiRenderManager(
                        A2uiConfig.defaults(), CATALOG, new ScriptedModel(List.of()), null);
        assertTrue(defaults.renderPrompt().contains("at most 50 components"));

        A2uiRenderManager tight =
                new A2uiRenderManager(
                        A2uiConfig.builder().maxComponents(3).build(),
                        CATALOG,
                        new ScriptedModel(List.of()),
                        null);
        assertTrue(tight.renderPrompt().contains("at most 3 components"));

        // 上限是服务端硬约束：宿主换掉提示词也必须带上
        A2uiRenderManager overridden =
                new A2uiRenderManager(
                        A2uiConfig.defaults(),
                        CATALOG,
                        new ScriptedModel(List.of()),
                        null,
                        "CUSTOM");
        assertTrue(overridden.renderPrompt().startsWith("CUSTOM"));
        assertTrue(overridden.renderPrompt().contains("at most 50 components"));
    }

    @Test
    void renderTimeoutFailsWithOurReasonInsteadOfTheFrameworkToolTimeout() {
        A2uiConfig config = A2uiConfig.builder().renderTimeout(Duration.ofMillis(300)).build();
        A2uiRenderManager manager =
                new A2uiRenderManager(config, CATALOG, new NeverModel(), renderer(config), null);

        A2uiValidationException failure =
                assertThrows(
                        A2uiValidationException.class,
                        () ->
                                manager.generate(
                                                RuntimeContext.builder()
                                                        .sessionId("render-timeout")
                                                        .build(),
                                                "Show a dashboard",
                                                null)
                                        .block());
        assertTrue(failure.getMessage().contains("timed out after PT0.3S"), failure.getMessage());
        assertFalse(failure.getMessage().contains("PT5M"), failure.getMessage());
    }

    @Test
    void renderPromptCarriesCatalogGuideByDefault() {
        A2uiRenderManager manager =
                new A2uiRenderManager(
                        A2uiConfig.defaults(), CATALOG, new ScriptedModel(List.of()), null);
        String prompt = manager.renderPrompt();
        assertTrue(prompt.contains("# A2UI Component Catalog"));
        assertTrue(prompt.contains("### Text"));
        assertTrue(prompt.contains("`a2ui_present`"));
        assertFalse(prompt.contains("a2ui_catalog"));
    }

    @Test
    void catalogInPromptOffFallsBackToOnDemandTool() {
        A2uiConfig config = A2uiConfig.builder().catalogInPrompt(false).build();
        String prompt =
                new A2uiRenderManager(config, CATALOG, new ScriptedModel(List.of()), null)
                        .renderPrompt();
        assertTrue(prompt.contains("call `a2ui_catalog` once"));
        assertFalse(prompt.contains("# A2UI Component Catalog"));

        ScriptedModel child = new ScriptedModel(List.of(textResponse("done")));
        new A2uiRenderTool(renderer(config, child))
                .callAsync(
                        ToolCallParam.builder()
                                .runtimeContext(RuntimeContext.builder().sessionId("s").build())
                                .input(Map.of("description", "Anything"))
                                .build())
                .block();
        assertEquals(2, child.toolNames.size());
        assertTrue(child.toolNames.contains("a2ui_catalog"), child.toolNames.toString());
        assertTrue(child.toolNames.contains("a2ui_present"), child.toolNames.toString());
    }

    @Test
    void renderToolFailsWhenChildNeverSubmitsValidTree() {
        ScriptedModel child = new ScriptedModel(List.of(textResponse("I can't design that")));
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults(), child));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder().sessionId("s").build())
                                        .input(Map.of("description", "Whatever"))
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("ended without an accepted component submission"));
    }

    @Test
    void renderToolRequiresDescription() {
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults()));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(RuntimeContext.empty())
                                        .input(Map.of())
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("`description` must be a non-blank"));
    }

    @Test
    void renderToolFailsFastWithoutRenderModel() {
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults()));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(RuntimeContext.empty())
                                        .input(Map.of("description", "Anything"))
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("no render model available"));
    }

    @Test
    void askUserQuestionSuspendsWithFormEnvelope() {
        AskUserQuestionTool tool = new AskUserQuestionTool(renderer(A2uiConfig.defaults()), true);
        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(RuntimeContext.builder().sessionId("s").build())
                        .input(
                                Map.of(
                                        "questions",
                                        List.of(
                                                Map.of(
                                                        "id", "q1",
                                                        "question", "Pick one",
                                                        "type", "select",
                                                        "options", List.of("a", "b")))))
                        .build();

        ToolSuspendException suspend =
                assertThrows(ToolSuspendException.class, () -> tool.callAsync(param).block());
        String envelope = suspend.getReason();
        assertTrue(envelope.contains("\"createSurface\":{"), envelope);
        assertTrue(envelope.contains("\"component\":\"ChoicePicker\""), envelope);
        assertTrue(envelope.contains("\"@path\":\"/answers/q1\""), envelope);
        assertTrue(envelope.contains("\"name\":\"submit\""), envelope);
        assertTrue(envelope.contains("\"dataModel\":{\"answers\":{\"q1\":[]}}"), envelope);
    }

    @Test
    void askUserQuestionCompilesTheDeclarativeFormSpec() {
        AskUserQuestionTool tool = new AskUserQuestionTool(renderer(A2uiConfig.defaults()), true);
        Map<String, Object> form =
                Map.of(
                        "title", "Order details",
                        "description", "Needed before checkout",
                        "submitText", "Confirm",
                        "questions",
                                List.of(
                                        Map.of(
                                                "id",
                                                "note",
                                                "question",
                                                "Notes",
                                                "type",
                                                "textarea"),
                                        Map.of(
                                                "id",
                                                "cutlery",
                                                "question",
                                                "Cutlery?",
                                                "type",
                                                "checkbox"),
                                        Map.of(
                                                "id",
                                                "size",
                                                "question",
                                                "Size",
                                                "type",
                                                "radio",
                                                "options",
                                                List.of("M", "L")),
                                        Map.of(
                                                "id",
                                                "addr",
                                                "question",
                                                "Address",
                                                "type",
                                                "text",
                                                "placeholder",
                                                "Street")));
        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(RuntimeContext.builder().sessionId("form").build())
                        .input(form)
                        .build();

        ToolSuspendException suspend =
                assertThrows(ToolSuspendException.class, () -> tool.callAsync(param).block());
        String envelope = suspend.getReason();
        // The basic catalog has no heading component; its Text renders simple Markdown.
        assertTrue(envelope.contains("\"text\":\"## Order details\""), envelope);
        assertTrue(envelope.contains("\"variant\":\"caption\""), envelope);
        assertTrue(envelope.contains("\"component\":\"TextField\""), envelope);
        assertTrue(envelope.contains("\"variant\":\"longText\""), envelope);
        assertTrue(envelope.contains("\"component\":\"CheckBox\""), envelope);
        assertTrue(envelope.contains("\"variant\":\"mutuallyExclusive\""), envelope);
        assertTrue(envelope.contains("\"placeholder\":\"Street\""), envelope);
        assertTrue(envelope.contains("\"text\":\"Confirm\""), envelope);
        assertTrue(envelope.contains("\"cutlery\":false"), envelope);
        assertTrue(envelope.contains("\"note\":\"\""), envelope);
    }

    @Test
    void askUserQuestionBindsRequiredChecksAndMultiSelectAnswers() {
        AskUserQuestionTool tool = new AskUserQuestionTool(renderer(A2uiConfig.defaults()), true);
        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(RuntimeContext.builder().sessionId("form-checks").build())
                        .input(
                                Map.of(
                                        "questions",
                                        List.of(
                                                Map.of(
                                                        "id", "name",
                                                        "question", "Name",
                                                        "type", "text",
                                                        "required", true),
                                                Map.of(
                                                        "id", "email",
                                                        "question", "Email",
                                                        "type", "text",
                                                        "required", true),
                                                Map.of(
                                                        "id", "tags",
                                                        "question", "Tags",
                                                        "type", "multi_select",
                                                        "options", List.of("a", "b")))))
                        .build();

        ToolSuspendException suspend =
                assertThrows(ToolSuspendException.class, () -> tool.callAsync(param).block());
        String envelope = suspend.getReason();
        assertTrue(envelope.contains("\"@call\":\"required\""), envelope);
        assertTrue(envelope.contains("\"message\":\"请填写「Name」\""), envelope);
        assertTrue(envelope.contains("\"@call\":\"and\""), envelope);
        assertTrue(envelope.contains("\"answers\":{\"@path\":\"/answers\"}"), envelope);
        assertTrue(envelope.contains("\"multiSelect\":[\"tags\"]"), envelope);
        assertTrue(envelope.contains("\"tags\":[]"), envelope);
        assertTrue(envelope.contains("\"variant\":\"multipleSelection\""), envelope);
    }

    /** {@code and} needs at least two values, so one required question is its own condition. */
    @Test
    void askUserQuestionWithOneRequiredFieldSkipsTheAndCall() {
        AskUserQuestionTool tool = new AskUserQuestionTool(renderer(A2uiConfig.defaults()), true);
        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(RuntimeContext.builder().sessionId("form-one").build())
                        .input(
                                Map.of(
                                        "questions",
                                        List.of(
                                                Map.of(
                                                        "id", "name",
                                                        "question", "Name",
                                                        "type", "text",
                                                        "required", true))))
                        .build();

        ToolSuspendException suspend =
                assertThrows(ToolSuspendException.class, () -> tool.callAsync(param).block());
        String envelope = suspend.getReason();
        assertTrue(envelope.contains("\"@call\":\"required\""), envelope);
        assertFalse(envelope.contains("\"@call\":\"and\""), envelope);
        assertFalse(envelope.contains("multiSelect"), envelope);
    }

    @Test
    void plainAskSuspendsWithCanonicalQuestionsJson() {
        AskUserQuestionTool tool = new AskUserQuestionTool(null, false);
        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(RuntimeContext.empty())
                        .input(
                                Map.of(
                                        "questions",
                                        List.of(
                                                Map.of(
                                                        "id", "q1",
                                                        "question", "Pick one",
                                                        "type", "select",
                                                        "options", List.of("a", "b")))))
                        .build();
        ToolSuspendException suspend =
                assertThrows(ToolSuspendException.class, () -> tool.callAsync(param).block());
        String payload = suspend.getReason();
        // Plain clarification card contract (spec §9.1): canonical questions JSON, no envelope.
        assertTrue(payload.startsWith("{\"questions\":["));
        assertTrue(payload.contains("\"id\":\"q1\""));
        assertFalse(payload.contains("createSurface"));
    }

    @Test
    void enableA2uiWithoutRendererFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new AskUserQuestionTool(null, true));
    }

    @Test
    void askUserQuestionRejectsInvalidQuestions() {
        AskUserQuestionTool tool = new AskUserQuestionTool(renderer(A2uiConfig.defaults()), true);
        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(RuntimeContext.empty())
                        .input(
                                Map.of(
                                        "questions",
                                        List.of(
                                                Map.of(
                                                        "id", "q1",
                                                        "question", "Pick",
                                                        "type", "select"))))
                        .build();
        ToolResultBlock result = tool.callAsync(param).block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("requires non-empty `options`"));
    }

    @Test
    void systemPromptAppendCarriesUsageConstraints() {
        A2uiMiddleware mw = new A2uiMiddleware(A2uiConfig.defaults());
        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE").block();
        assertTrue(prompt.startsWith("BASE"));
        assertTrue(prompt.contains("a2ui_render"));
        assertTrue(prompt.contains("`surfaceId` is managed by the system"));
        assertTrue(prompt.contains("end your turn there"));
    }

    @Test
    void userPreferenceAppendsToMainAndRenderPrompts() {
        A2uiConfig config = A2uiConfig.builder().appendPreference("深色主题\n表单字段紧凑排列").build();
        String prompt =
                new A2uiMiddleware(config)
                        .onSystemPrompt(null, RuntimeContext.empty(), "BASE")
                        .block();
        assertTrue(prompt.contains("## User UI preference"));
        assertTrue(prompt.contains("- 深色主题"));
        assertTrue(prompt.contains("- 表单字段紧凑排列"));

        A2uiRenderManager manager =
                new A2uiRenderManager(config, CATALOG, null, renderer(config), null);
        assertTrue(manager.renderPrompt().startsWith("You are a dedicated A2UI interface"));
        assertTrue(manager.renderPrompt().contains("## User UI preference"));
    }

    @Test
    void blankPreferenceAddsNothing() {
        A2uiConfig plain = A2uiConfig.defaults();
        assertEquals("", plain.preferenceBlock());
        A2uiRenderManager manager =
                new A2uiRenderManager(plain, CATALOG, null, renderer(plain), "CUSTOM");
        String prompt = manager.renderPrompt();
        // A custom prompt replaces only the base section; the catalog guide still ships with it.
        assertTrue(prompt.startsWith("CUSTOM"));
        assertTrue(prompt.contains("# A2UI Component Catalog"));
        assertFalse(prompt.contains("User UI preference"));
    }
}
