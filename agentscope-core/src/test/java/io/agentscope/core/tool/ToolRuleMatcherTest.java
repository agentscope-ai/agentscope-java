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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ToolRuleMatcherTest {

    @Test
    void reflectiveToolsReceiveEachCallsContextForDenyAskAndAllowRules() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ContextTool());
        ToolBase tool = (ToolBase) toolkit.getTool("context_tool");
        RuntimeContext alice = RuntimeContext.builder().userId("alice").build();
        RuntimeContext bob = RuntimeContext.builder().userId("bob").build();
        for (PermissionBehavior behavior :
                new PermissionBehavior[] {
                    PermissionBehavior.DENY, PermissionBehavior.ASK, PermissionBehavior.ALLOW
                }) {
            PermissionEngine engine =
                    new PermissionEngine(
                            PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build());
            engine.addRule(new PermissionRule("context_tool", "alice", behavior, "test"));
            assertEquals(
                    behavior, engine.checkPermission(tool, Map.of(), alice).block().getBehavior());
            assertEquals(
                    PermissionBehavior.DENY,
                    engine.checkPermission(tool, Map.of(), bob).block().getBehavior());
            assertEquals(
                    behavior, engine.checkPermission(tool, Map.of(), alice).block().getBehavior());
        }
    }

    @Test
    void contextOverloadPreservesLegacyToolOverrides() {
        ToolBase tool =
                new ToolBase(
                        ToolBase.builder()
                                .name("legacy")
                                .description("legacy")
                                .inputSchema(Map.of("type", "object"))) {
                    @Override
                    public boolean matchRule(String ruleContent, Map<String, Object> input) {
                        return ruleContent.equals(input.get("value"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.success("ok"));
                    }
                };
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build());
        engine.addRule(new PermissionRule("legacy", "secret", PermissionBehavior.DENY, "test"));
        assertEquals(
                PermissionBehavior.DENY,
                engine.checkPermission(tool, Map.of("value", "secret"), RuntimeContext.empty())
                        .block()
                        .getBehavior());
        assertEquals(
                PermissionBehavior.ALLOW,
                engine.checkPermission(tool, Map.of("value", "public")).block().getBehavior());
    }

    @Test
    void ordinaryReflectiveToolsKeepToolWideRulesOnly() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PlainTool());
        ToolBase tool = (ToolBase) toolkit.getTool("plain");
        assertTrue(tool.matchRule(null, Map.of()));
        assertFalse(tool.matchRule("specific", Map.of()));
        assertFalse(tool.matchRule("specific", Map.of(), RuntimeContext.empty()));
    }

    @Test
    void presetsWinWithoutMutatingInputOrLeakingIntoExternalOverrides() {
        Toolkit toolkit = new Toolkit();
        toolkit.registration()
                .tool(new PlainTool())
                .presetParameters(Map.of("plain", Map.of("value", "preset")))
                .apply();
        Map<String, Object> raw = Map.of("value", "model");
        Map<String, Object> effective =
                toolkit.effectiveInput("plain", raw, ToolRequestConfig.NONE);
        assertEquals("preset", effective.get("value"));
        effective.put("value", "changed");
        assertEquals("preset", toolkit.effectiveInput("plain", raw, null).get("value"));
        assertEquals("model", raw.get("value"));
        SchemaOnlyTool external = new SchemaOnlyTool("plain", "external", Map.of("type", "object"));
        ToolRequestConfig override =
                new ToolRequestConfig(
                        Map.of("plain", external), ToolMergeMode.MERGE_EXTERNAL_PRIORITY);
        assertEquals(raw, toolkit.effectiveInput("plain", raw, override));
        assertEquals(
                raw,
                toolkit.effectiveInput(
                        "plain",
                        raw,
                        new ToolRequestConfig(Map.of(), ToolMergeMode.EXTERNAL_ONLY)));
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    @SuppressWarnings("deprecation")
    void permissionsAndExecutionUseTheSameToolkitDefaults(boolean streaming, boolean override) {
        ToolExecutionContext defaults =
                ToolExecutionContext.builder()
                        .register(new ContextValue("toolkit"))
                        .register("fallback", String.class, "fallback")
                        .build();
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().defaultContext(defaults).build());
        DefaultContextTool tool = new DefaultContextTool();
        toolkit.registerTool(tool);
        RuntimeContext.Builder context =
                RuntimeContext.builder().userId("alice").sessionId("session");
        if (override) {
            context.put(ContextValue.class, new ContextValue("request"));
        }
        String expected = (override ? "request" : "toolkit") + ":fallback:alice:session";
        PermissionContextState permissions =
                PermissionContextState.builder()
                        .mode(PermissionMode.DONT_ASK)
                        .addAllowRule(
                                "default_context",
                                new PermissionRule(
                                        "default_context",
                                        expected,
                                        PermissionBehavior.ALLOW,
                                        "test"))
                        .build();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModelBase model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "scripted";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> schemas, GenerateOptions options) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                modelCalls.getAndIncrement() == 0
                                                        ? List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("context")
                                                                        .name("default_context")
                                                                        .input(Map.of())
                                                                        .content("{}")
                                                                        .build())
                                                        : List.of(
                                                                TextBlock.builder()
                                                                        .text("done")
                                                                        .build()))
                                        .build());
                    }
                };
        ReActAgent agent =
                ReActAgent.builder()
                        .name("context-test")
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(permissions)
                        .maxIters(3)
                        .build();
        List<Msg> input = List.of(Msg.builder().role(MsgRole.USER).textContent("Run tool").build());
        try {
            if (streaming) {
                assertNotNull(
                        agent.streamEvents(input, context.build())
                                .collectList()
                                .block(Duration.ofSeconds(10)));
            } else {
                assertNotNull(agent.call(input, context.build()).block(Duration.ofSeconds(10)));
            }
        } finally {
            agent.close();
        }
        assertEquals(List.of(expected), tool.checked);
        assertEquals(expected, tool.executed.get());
        assertEquals(2, modelCalls.get());
    }

    private record ContextValue(String value) {}

    static class DefaultContextTool implements ToolRuleMatcher {
        final List<String> checked = new ArrayList<>();
        final AtomicReference<String> executed = new AtomicReference<>();

        @Tool(name = "default_context", description = "Reads default context")
        public String call(RuntimeContext context) {
            String resolved = resolve(context);
            executed.set(resolved);
            return resolved;
        }

        @Override
        public boolean matchRule(
                String ruleContent, Map<String, Object> input, RuntimeContext context) {
            String resolved = resolve(context);
            checked.add(resolved);
            return ruleContent.equals(resolved);
        }

        private static String resolve(RuntimeContext context) {
            ContextValue value = context.asToolExecutionContext().get(ContextValue.class);
            return (value == null ? "missing" : value.value())
                    + ":"
                    + context.asToolExecutionContext().get("fallback", String.class)
                    + ":"
                    + context.getUserId()
                    + ":"
                    + context.getSessionId();
        }
    }

    static class PlainTool {
        @Tool(name = "plain", description = "Plain tool")
        public String call(@ToolParam(name = "value") String value) {
            return value;
        }
    }

    static class ContextTool implements ToolRuleMatcher {
        @Tool(name = "context_tool", description = "Context tool")
        public String call() {
            return "ok";
        }

        @Override
        public boolean matchRule(
                String ruleContent, Map<String, Object> input, RuntimeContext context) {
            return ruleContent.equals(context.getUserId());
        }
    }
}
