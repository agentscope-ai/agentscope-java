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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelopeValidator;
import io.agentscope.extensions.a2ui.middleware.A2uiRenderStopMiddleware;
import io.agentscope.extensions.a2ui.tool.A2uiRenderTool;
import io.agentscope.extensions.a2ui.tool.A2uiTreeRenderTool;
import io.agentscope.extensions.a2ui.tool.AskUserQuestionTool;
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
            A2uiCatalog.load("a2ui/basic-catalog.json", "agentscope.io:a2ui/basic");

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

    private static Map<String, Object> comp(
            String id, String component, Map<String, Object> props) {
        return Map.of("id", id, "component", component, "props", props);
    }

    private static ToolUseBlock toolCall(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).build();
    }

    private static String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    @Test
    void builtInCatalogLoadsAllComponentsAndGuideText() {
        assertTrue(CATALOG.componentNames().contains("Heading"));
        assertTrue(CATALOG.componentNames().contains("FileDrop"));
        assertEquals(22, CATALOG.componentNames().size());
        String guide = CATALOG.guideText();
        assertTrue(guide.contains("### Heading"));
        assertTrue(guide.contains("agentscope.io:a2ui/basic"));
        // Channel-agnostic: the prompt/tool description names the submit tool, the guide does not.
        assertFalse(guide.contains("a2ui_"));
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
                                        comp("c1", "Heading", Map.of("text", "Hi", "level", 2)),
                                        comp("c2", "Text", Map.of("text", "body"))),
                                CATALOG,
                                50)
                        .isEmpty());
    }

    @Test
    void validatorRejectsUnknownComponent() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("c1", "Spaceship", Map.of())), CATALOG, 50);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("unknown component \"Spaceship\""));
    }

    @Test
    void validatorRejectsMissingRequiredProps() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("c1", "Select", Map.of("name", "q"))), CATALOG, 50);
        assertTrue(errors.stream().anyMatch(e -> e.contains("missing required prop \"options\"")));
    }

    @Test
    void validatorRejectsWrongPropTypeAndEnumViolation() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(
                                comp("c1", "Heading", Map.of("text", 42)),
                                comp("c2", "Alert", Map.of("text", "t", "kind", "purple"))),
                        CATALOG,
                        50);
        assertTrue(errors.stream().anyMatch(e -> e.contains("should be string")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("must be one of")));
    }

    @Test
    void validatorRejectsDuplicateIdsAndEmptyOrNullList() {
        assertTrue(A2uiEnvelopeValidator.validate(List.of(), CATALOG, 50).size() == 1);
        assertTrue(A2uiEnvelopeValidator.validate(null, CATALOG, 50).size() == 1);
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(
                                comp("dup", "Text", Map.of("text", "a")),
                                comp("dup", "Text", Map.of("text", "b"))),
                        CATALOG,
                        50);
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate component id")));
    }

    @Test
    void validatorEnforcesMaxComponents() {
        List<Map<String, Object>> many =
                java.util.stream.IntStream.range(0, 5)
                        .mapToObj(i -> comp("c" + i, "Text", Map.of("text", "x")))
                        .toList();
        assertFalse(
                A2uiEnvelopeValidator.validate(many, CATALOG, 3).stream()
                        .noneMatch(e -> e.contains("at most 3")));
    }

    @Test
    void firstRenderCreatesSurfaceAndLaterRendersUpdateIt() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-a").build();
        A2uiRenderer renderer = renderer(A2uiConfig.defaults());
        String first =
                renderer.renderEnvelope(rc, List.of(comp("c1", "Text", Map.of("text", "a"))));
        String second =
                renderer.renderEnvelope(rc, List.of(comp("c1", "Text", Map.of("text", "b"))));

        assertTrue(first.contains("\"protocolVersion\":\"1.0\""));
        assertTrue(first.contains("\"messageType\":\"createSurface\""));
        assertTrue(first.contains("\"catalogId\":\"agentscope.io:a2ui/basic\""));
        assertTrue(second.contains("\"messageType\":\"updateComponents\""));

        String surfaceId = first.replaceAll(".*\"surfaceId\":\"(s-[0-9a-f]+)\".*", "$1");
        assertTrue(surfaceId.matches("s-[0-9a-f]{8}"), surfaceId);
        assertTrue(second.contains(surfaceId), "same surface reused within the run");
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
                                                        List.of(comp("c1", "Nope", Map.of()))))
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, invalid.getState());
        assertTrue(textOf(invalid).contains("unknown component"));
        assertTrue(sink.get() == null, "rejected submissions must not reach the sink");

        ToolResultBlock valid =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(rc)
                                        .input(
                                                Map.of(
                                                        "components",
                                                        List.of(
                                                                comp(
                                                                        "c1",
                                                                        "Heading",
                                                                        Map.of(
                                                                                "text", "Hi",
                                                                                "level", 2)))))
                                        .build())
                        .block();
        assertEquals(ToolResultState.RUNNING, valid.getState());
        assertEquals(textOf(valid), sink.get());
        assertTrue(sink.get().contains("\"messageType\":\"createSurface\""));
    }

    @Test
    void renderToolRunsChildAgentInSubmitAndDoneTurns() {
        ScriptedModel child =
                new ScriptedModel(
                        List.of(
                                toolUse(
                                        "t1",
                                        "a2ui_present",
                                        Map.of(
                                                "components",
                                                List.of(
                                                        comp(
                                                                "c1",
                                                                "Heading",
                                                                Map.of(
                                                                        "text", "Hi", "level",
                                                                        2))))),
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
        assertTrue(textOf(result).contains("\"messageType\":\"createSurface\""));
        assertEquals(2, child.calls.get());
        assertEquals(List.of("a2ui_present"), child.toolNames);
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
                                                List.of(comp("c1", "Nope", Map.of())))),
                                toolUse(
                                        "t2",
                                        "a2ui_present",
                                        Map.of(
                                                "components",
                                                List.of(comp("c1", "Text", Map.of("text", "ok"))))),
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
        assertTrue(textOf(result).contains("\"messageType\":\"createSurface\""));
        assertEquals(3, child.calls.get());
    }

    @Test
    void renderPromptCarriesCatalogGuideByDefault() {
        A2uiRenderManager manager =
                new A2uiRenderManager(
                        A2uiConfig.defaults(), CATALOG, new ScriptedModel(List.of()), null);
        String prompt = manager.renderPrompt();
        assertTrue(prompt.contains("# A2UI Component Catalog"));
        assertTrue(prompt.contains("### Heading"));
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
        assertTrue(envelope.contains("\"messageType\":\"createSurface\""));
        assertTrue(envelope.contains("\"component\":\"Select\""));
        assertTrue(envelope.contains("\"name\":\"q1\""));
        assertTrue(envelope.contains("\"action\":\"submit\""));
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
    void successfulRenderEmitsStopEvent() {
        A2uiRenderStopMiddleware mw = new A2uiRenderStopMiddleware(A2uiConfig.defaults());
        ToolResultEndEvent renderEnd =
                new ToolResultEndEvent("r1", "tc1", "a2ui_render", ToolResultState.SUCCESS);
        List<AgentEvent> events =
                mw.onActing(
                                null,
                                RuntimeContext.empty(),
                                new ActingInput(List.of(toolCall("tc1", "a2ui_render"))),
                                in -> Flux.just(renderEnd))
                        .collectList()
                        .block();
        assertEquals(2, events.size());
        assertInstanceOf(RequestStopEvent.class, events.get(1));
    }

    @Test
    void failedRenderDoesNotStop() {
        A2uiRenderStopMiddleware mw = new A2uiRenderStopMiddleware(A2uiConfig.defaults());
        ToolResultEndEvent renderError =
                new ToolResultEndEvent("r1", "tc1", "a2ui_render", ToolResultState.ERROR);
        List<AgentEvent> events =
                mw.onActing(
                                null,
                                RuntimeContext.empty(),
                                new ActingInput(List.of(toolCall("tc1", "a2ui_render"))),
                                in -> Flux.just(renderError))
                        .collectList()
                        .block();
        assertEquals(1, events.size());
        assertTrue(events.stream().noneMatch(e -> e instanceof RequestStopEvent));
    }

    @Test
    void mixedToolBatchDoesNotStopEvenWhenRenderSucceeds() {
        A2uiRenderStopMiddleware mw = new A2uiRenderStopMiddleware(A2uiConfig.defaults());
        ToolResultEndEvent otherEnd =
                new ToolResultEndEvent("r1", "tc1", "some_tool", ToolResultState.SUCCESS);
        ToolResultEndEvent renderEnd =
                new ToolResultEndEvent("r1", "tc2", "a2ui_render", ToolResultState.SUCCESS);
        List<AgentEvent> events =
                mw.onActing(
                                null,
                                RuntimeContext.empty(),
                                new ActingInput(
                                        List.of(
                                                toolCall("tc1", "some_tool"),
                                                toolCall("tc2", "a2ui_render"))),
                                in -> Flux.just(otherEnd, renderEnd))
                        .collectList()
                        .block();
        assertEquals(2, events.size());
        assertTrue(events.stream().noneMatch(e -> e instanceof RequestStopEvent));
    }

    @Test
    void systemPromptAppendCarriesUsageConstraints() {
        A2uiRenderStopMiddleware mw = new A2uiRenderStopMiddleware(A2uiConfig.defaults());
        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE").block();
        assertTrue(prompt.startsWith("BASE"));
        assertTrue(prompt.contains("a2ui_render"));
        assertTrue(prompt.contains("`surfaceId` is managed by the system"));
    }

    @Test
    void userPreferenceAppendsToMainAndRenderPrompts() {
        A2uiConfig config = A2uiConfig.builder().appendPreference("深色主题\n表单字段紧凑排列").build();
        String prompt =
                new A2uiRenderStopMiddleware(config)
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
