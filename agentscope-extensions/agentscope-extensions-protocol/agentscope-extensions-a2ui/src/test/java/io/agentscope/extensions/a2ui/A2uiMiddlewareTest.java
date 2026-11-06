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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.a2ui.tool.A2uiRenderTool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies {@link A2uiMiddleware} wires the A2UI capability through public framework surfaces
 * only: {@code builder.middleware(...)} plus an explicit {@link ToolInstallingMiddleware} install
 * onto the built agent's own toolkit.
 */
class A2uiMiddlewareTest {

    @TempDir Path workspace;

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("ok").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    /**
     * Assembles an agent the way a hosting application does: middlewares are mounted before {@code
     * build()}, then each {@link ToolInstallingMiddleware} installs its tools on the built agent's
     * own toolkit.
     */
    private HarnessAgent buildWith(MiddlewareBase... middlewares) throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("a2ui-middleware-test")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace));
        for (MiddlewareBase middleware : middlewares) {
            builder.middleware(middleware);
        }
        HarnessAgent agent = builder.build();
        Toolkit toolkit = agent.getDelegate().getToolkit();
        for (MiddlewareBase middleware : middlewares) {
            if (middleware instanceof ToolInstallingMiddleware installer) {
                installer.installTools(toolkit);
            }
        }
        return agent;
    }

    private static List<String> toolNamesOf(HarnessAgent agent) {
        return agent.getDelegate().getToolkit().getToolSchemas().stream()
                .map(ToolSchema::getName)
                .toList();
    }

    @Test
    void attachedMiddlewareRegistersParentToolsWithoutCatalog() throws Exception {
        HarnessAgent agent = buildWith(new A2uiMiddleware());
        List<String> toolNames = toolNamesOf(agent);
        assertTrue(toolNames.contains("a2ui_render"));
        assertTrue(toolNames.contains("ask_user_question"));
        // The component DSL (catalog guide + a2ui_present tree submit) lives only on the render
        // sub-agent, so none of it may leak onto the parent toolkit.
        assertFalse(toolNames.contains("a2ui_catalog"));
        assertFalse(toolNames.contains("a2ui_present"));
        assertFalse(toolNames.contains("a2ui_ask_user_question"));
    }

    @Test
    void clarificationMiddlewareRegistersPlainAskTool() throws Exception {
        HarnessAgent agent = buildWith(new ClarificationMiddleware());
        List<String> toolNames = toolNamesOf(agent);
        assertTrue(toolNames.contains("ask_user_question"));
        assertFalse(toolNames.contains("a2ui_render"));
    }

    @Test
    void withoutMiddlewareNoA2uiTools() throws Exception {
        HarnessAgent agent = buildWith();
        List<String> toolNames = toolNamesOf(agent);
        assertFalse(toolNames.contains("a2ui_catalog"));
        assertFalse(toolNames.contains("a2ui_render"));
        assertFalse(toolNames.contains("a2ui_present"));
        assertFalse(toolNames.contains("a2ui_ask_user_question"));
    }

    @Test
    void brokenCatalogFailsFastAtConstruction() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new A2uiMiddleware(
                                A2uiConfig.builder()
                                        .catalogResource("a2ui/does-not-exist.json")
                                        .build()));
    }

    @Test
    void installToolsNeverOverwritesForeignHostToolOfSameName() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ForeignAskTool());

        new A2uiMiddleware().installTools(toolkit);

        assertInstanceOf(ForeignAskTool.class, toolkit.getTool("ask_user_question"));
        assertInstanceOf(A2uiRenderTool.class, toolkit.getTool("a2ui_render"));
    }

    @Test
    void reasoningSeesProseWhereTheModelNeverNeedsTheEnvelope() {
        String envelope =
                "{\"protocolVersion\":\"1.0\",\"messageType\":\"updateComponents\","
                        + "\"surfaceId\":\"s-abcd1234\",\"catalogId\":\"c\",\"components\":["
                        + "{\"id\":\"h1\",\"component\":\"Heading\",\"props\":{\"text\":\"Hi\"}},"
                        + "{\"id\":\"t1\",\"component\":\"Text\",\"props\":{\"text\":\"body\"}}],"
                        + "\"note\":\"Already delivered; stop.\"}";
        Msg ui = toolResultMsg("a2ui_render", envelope);
        Msg failure = toolResultMsg("a2ui_render", "Error: A2UI render failed: boom");
        Msg ask = toolResultMsg("ask_user_question", envelope);
        Msg user =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("show the report").build())
                        .build();

        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        new A2uiMiddleware()
                .onReasoning(
                        null,
                        RuntimeContext.empty(),
                        new ReasoningInput(List.of(user, ui, failure, ask), List.of(), null),
                        input -> {
                            seen.set(input);
                            return Flux.empty();
                        })
                .blockLast();

        assertTrue(resultText(ui).contains("protocolVersion"), "history keeps the envelope");
        String shownUi = resultText(seen.get().messages().get(1));
        assertFalse(shownUi.contains("protocolVersion"), shownUi);
        assertTrue(shownUi.contains("s-abcd1234"), shownUi);
        assertTrue(shownUi.contains("Heading"), shownUi);
        assertTrue(shownUi.contains("Already delivered; stop."), shownUi);
        assertEquals("Error: A2UI render failed: boom", resultText(seen.get().messages().get(2)));
        assertTrue(
                resultText(seen.get().messages().get(3)).contains("protocolVersion"),
                "only the render tool's result is the UI envelope");
        assertEquals("show the report", seen.get().messages().get(0).getTextContent());
    }

    private static Msg toolResultMsg(String toolName, String output) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id("call-" + toolName)
                                .name(toolName)
                                .output(TextBlock.builder().text(output).build())
                                .build())
                .build();
    }

    private static String resultText(Msg msg) {
        return msg.getContentBlocks(ToolResultBlock.class).get(0).getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(java.util.stream.Collectors.joining());
    }

    /** A host tool that happens to occupy the A2UI ask name. */
    private static final class ForeignAskTool implements AgentTool {

        @Override
        public String getName() {
            return "ask_user_question";
        }

        @Override
        public String getDescription() {
            return "host-owned tool occupying the A2UI ask name";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("host"));
        }
    }
}
