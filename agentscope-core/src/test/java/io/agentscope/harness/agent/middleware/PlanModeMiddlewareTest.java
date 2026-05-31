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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PlanModeMiddlewareTest {

    private static final Predicate<String> READ_ONLY = name -> name.equals("read_file");

    private static ToolUseBlock call(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(java.util.Map.of()).build();
    }

    private static PlanModeManager manager(Path project, Path workspace) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        return new PlanModeManager(new WorkspaceManager(workspace, fs), null);
    }

    @Test
    void deniesMutatingToolsButAllowsReadOnlyAndPlanTools(
            @TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        manager.enter(state);
        StubAgent agent = new StubAgent("tester", state);

        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        List<ToolUseBlock> calls =
                List.of(
                        call("1", "read_file"),
                        call("2", "write_file"),
                        call("3", "plan_exit"),
                        call("4", "todo_write"));

        AtomicReference<ActingInput> forwarded = new AtomicReference<>();
        List<AgentEvent> events =
                mw.onActing(
                                agent,
                                new ActingInput(calls),
                                ai -> {
                                    forwarded.set(ai);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();

        // Only read_file + plan_exit + todo_write reach the core acting logic.
        List<String> forwardedNames =
                forwarded.get().toolCalls().stream().map(ToolUseBlock::getName).toList();
        assertEquals(List.of("read_file", "plan_exit", "todo_write"), forwardedNames);

        // The single denied call produced a denied tool-result message in context.
        long denialMsgs = state.contextMutable().stream().filter(m -> m.getRole() != null).count();
        assertEquals(1, denialMsgs);

        // Start + delta + end for the one denied call.
        assertEquals(3, events.size());
    }

    @Test
    void passesThroughWhenPlanModeInactive(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        StubAgent agent = new StubAgent("tester", state);
        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        List<ToolUseBlock> calls = List.of(call("1", "write_file"));
        AtomicReference<ActingInput> forwarded = new AtomicReference<>();
        mw.onActing(
                        agent,
                        new ActingInput(calls),
                        ai -> {
                            forwarded.set(ai);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        assertEquals(1, forwarded.get().toolCalls().size());
        assertTrue(state.contextMutable().isEmpty());
    }

    @Test
    void systemPromptBannerOnlyWhenActive(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        StubAgent agent = new StubAgent("tester", state);
        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        String inactive = mw.onSystemPrompt(agent, "base").block();
        assertFalse(inactive.contains("PLAN MODE"));

        manager.enter(state);
        String active = mw.onSystemPrompt(agent, "base").block();
        assertTrue(active.contains("PLAN MODE"));
        assertTrue(active.startsWith("base"));
    }

    /** Minimal Agent stub exposing only name + state. */
    private static final class StubAgent implements Agent {
        private final String name;
        private final AgentState state;

        StubAgent(String name, AgentState state) {
            this.name = name;
            this.state = state;
        }

        @Override
        public AgentState getAgentState() {
            return state;
        }

        @Override
        public String getAgentId() {
            return "id-" + name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void interrupt() {}

        @Override
        public void interrupt(Msg msg) {}

        @Override
        public Mono<Msg> call(List<Msg> msgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> observe(Msg msg) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> observe(List<Msg> msgs) {
            return Mono.empty();
        }
    }
}
