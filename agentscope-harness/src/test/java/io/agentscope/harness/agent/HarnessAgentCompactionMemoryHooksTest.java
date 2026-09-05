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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessAgentCompactionMemoryHooksTest {

    @TempDir Path workspace;

    @Test
    void disableMemoryHooksSkipsCompactionFlushAndKeepsOffload() throws Exception {
        RoutingModel model = new RoutingModel();
        CompactionConfig config = normalCompactionConfig(true, true);

        try (HarnessAgent agent = buildAgent(model, config, true)) {
            RuntimeContext context = context("normal-session");
            assertNotNull(agent.call(userMessage("first request"), context).block());
            assertNotNull(agent.call(userMessage("second request"), context).block());
        }

        assertEquals(0, model.flushCalls.get());
        assertEquals(1, model.summaryCalls.get());
        assertFalse(hasDailyMemoryFile());
        assertTrue(hasFileNamed("normal-session" + WorkspaceConstants.SESSION_CONTEXT_EXT));
    }

    @Test
    void enabledMemoryHooksPreserveConfiguredCompactionFlush() throws Exception {
        RoutingModel model = new RoutingModel();
        CompactionConfig config = normalCompactionConfig(true, false);

        try (HarnessAgent agent = buildAgent(model, config, false)) {
            RuntimeContext context = context("enabled-session");
            assertNotNull(agent.call(userMessage("first request"), context).block());
            assertNotNull(agent.call(userMessage("second request"), context).block());
        }

        assertEquals(1, model.flushCalls.get());
        assertEquals(1, model.summaryCalls.get());
        assertTrue(hasDailyMemoryFile());
    }

    @Test
    void explicitDisabledCompactionFlushRemainsDisabled() throws Exception {
        RoutingModel model = new RoutingModel();
        CompactionConfig config = normalCompactionConfig(false, false);

        try (HarnessAgent agent = buildAgent(model, config, false)) {
            RuntimeContext context = context("explicit-disabled-session");
            assertNotNull(agent.call(userMessage("first request"), context).block());
            assertNotNull(agent.call(userMessage("second request"), context).block());
        }

        assertEquals(0, model.flushCalls.get());
        assertEquals(1, model.summaryCalls.get());
        assertFalse(hasDailyMemoryFile());
    }

    @Test
    void disableMemoryHooksSkipsEmergencyCompactionFlush() throws Exception {
        RoutingModel model = new RoutingModel();
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerMessages(0)
                        .triggerTokens(Integer.MAX_VALUE)
                        .keepTokens(0)
                        .flushBeforeCompact(true)
                        .offloadBeforeCompact(false)
                        .prune(null)
                        .build();

        try (HarnessAgent agent = buildAgent(model, config, true)) {
            RuntimeContext context = context("overflow-session");
            for (int i = 0; i < 11; i++) {
                assertNotNull(agent.call(userMessage("request " + i), context).block());
            }

            model.overflowNextReasoning.set(true);
            assertNotNull(agent.call(userMessage("trigger overflow"), context).block());
        }

        assertEquals(0, model.flushCalls.get());
        assertEquals(1, model.summaryCalls.get());
        assertFalse(hasDailyMemoryFile());
    }

    private HarnessAgent buildAgent(
            RoutingModel model, CompactionConfig config, boolean disableMemoryHooks) {
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("compaction-memory-hooks-test")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.never())
                                        .build())
                        .compaction(config)
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .disableDynamicSkills()
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableWorkspaceContext()
                        .disableAtPathExpansion()
                        .disableToolResultEviction()
                        .disableToolsConfig()
                        .enableAgentTracingLog(false);
        if (disableMemoryHooks) {
            builder.disableMemoryHooks();
        }
        return builder.build();
    }

    private static CompactionConfig normalCompactionConfig(
            boolean flushBeforeCompact, boolean offloadBeforeCompact) {
        return CompactionConfig.builder()
                .triggerMessages(2)
                .triggerTokens(Integer.MAX_VALUE)
                .keepMessages(1)
                .keepTokens(0)
                .flushBeforeCompact(flushBeforeCompact)
                .offloadBeforeCompact(offloadBeforeCompact)
                .prune(null)
                .build();
    }

    private static RuntimeContext context(String sessionId) {
        return RuntimeContext.builder().userId("test-user").sessionId(sessionId).build();
    }

    private static Msg userMessage(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private boolean hasDailyMemoryFile() throws IOException {
        String expectedName = LocalDate.now() + ".md";
        try (Stream<Path> files = Files.walk(workspace)) {
            return files.filter(Files::isRegularFile)
                    .anyMatch(
                            path ->
                                    expectedName.equals(path.getFileName().toString())
                                            && path.getParent() != null
                                            && WorkspaceConstants.MEMORY_DIR.equals(
                                                    path.getParent().getFileName().toString()));
        }
    }

    private boolean hasFileNamed(String fileName) throws IOException {
        try (Stream<Path> files = Files.walk(workspace)) {
            return files.filter(Files::isRegularFile)
                    .anyMatch(path -> fileName.equals(path.getFileName().toString()));
        }
    }

    private static final class RoutingModel extends ChatModelBase {

        private final AtomicInteger flushCalls = new AtomicInteger();
        private final AtomicInteger summaryCalls = new AtomicInteger();
        private final AtomicBoolean overflowNextReasoning = new AtomicBoolean();

        @Override
        public String getModelName() {
            return "compaction-memory-hooks-test-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String rendered =
                    messages.stream().map(Msg::getTextContent).collect(Collectors.joining("\n"));
            if (rendered.contains("You are a memory extraction assistant")) {
                flushCalls.incrementAndGet();
                return response("- extracted memory");
            }
            if (rendered.contains("Context Extraction Assistant")) {
                summaryCalls.incrementAndGet();
                return response("compacted summary");
            }
            if (overflowNextReasoning.compareAndSet(true, false)) {
                return Flux.error(new IllegalStateException("context_length_exceeded"));
            }
            return response("assistant response");
        }

        private static Flux<ChatResponse> response(String text) {
            return Flux.just(
                    new ChatResponse(
                            "test-response",
                            List.of(TextBlock.builder().text(text).build()),
                            null,
                            Map.of(),
                            "stop"));
        }
    }
}
