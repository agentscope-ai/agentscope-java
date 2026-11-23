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
package io.agentscope.harness.agent.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.ModelCallPolicy;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessInlineMemoryAccountingTest {
    @TempDir Path directory;

    @Test
    void extractionRetainsOriginalIdentityAndRecordsUsageBeforeTheTurnEnds() {
        verify(false, false);
    }

    @Test
    void deniedExtractionDoesNotDispatchAnUnaccountedBackgroundModel() {
        verify(true, false);
    }

    @Test
    void consolidationUsesTheSameAuthorizedAndRecordedCallBoundary() {
        verify(false, true);
    }

    private void verify(boolean denySummary, boolean extractMemory) {
        var dispatches = new AtomicInteger();
        var checks = new AtomicInteger();
        var settlements = new AtomicInteger();
        var logStore = new InMemoryThreadJournalStore();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "memory-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () -> {
                                    int call = dispatches.incrementAndGet();
                                    return Flux.just(
                                            ChatResponse.builder()
                                                    .content(
                                                            List.of(
                                                                    TextBlock.builder()
                                                                            .text(
                                                                                    call == 1
                                                                                            ? "done"
                                                                                            : extractMemory
                                                                                                    ? "- Release"
                                                                                                          + " is on"
                                                                                                          + " Friday"
                                                                                                    : "NO_REPLY")
                                                                            .build()))
                                                    .usage(new ChatUsage(7, 3, 0))
                                                    .build());
                                });
                    }
                };
        var policy =
                new ModelCallPolicy() {
                    public void beforeCall(String id, String name, RuntimeContext context) {
                        assertEquals("user", context.getUserId());
                        assertEquals("session", context.getSessionId());
                        int call = checks.incrementAndGet();
                        if (denySummary && call == 2) throw new IllegalStateException("revoked");
                    }

                    public void afterCall(
                            String id,
                            String name,
                            String status,
                            ChatUsage usage,
                            RuntimeContext context) {
                        assertEquals("user", context.getUserId());
                        assertEquals(10, usage.getInputTokens() + usage.getOutputTokens());
                        settlements.incrementAndGet();
                    }
                };
        try (var agent =
                HarnessAgent.builder()
                        .memory(MemoryConfig.builder().consolidationMinGap(Duration.ZERO).build())
                        .name("inline-memory")
                        .model(model)
                        .workspace(directory)
                        .threadJournalStore(logStore)
                        .disableCompaction()
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableMemoryTools()
                        .disableToolResultEviction()
                        .build()) {
            var context =
                    RuntimeContext.builder()
                            .userId("user")
                            .sessionId("session")
                            .put(ModelCallPolicy.CONTEXT_KEY, policy)
                            .put(MemoryFlushMiddleware.INLINE_CONTEXT_KEY, true)
                            .build();
            agent.call(List.of(new UserMessage("remember the release")), context)
                    .block(Duration.ofSeconds(10));
            int expected = denySummary ? 1 : extractMemory ? 3 : 2;
            assertEquals(extractMemory ? 3 : 2, checks.get());
            assertEquals(expected, dispatches.get());
            assertEquals(expected, settlements.get());
            var events = agent.threadJournal(context).readAfter(0, 1000);
            assertEquals(
                    expected,
                    events.stream().filter(event -> "model/end".equals(event.type())).count());
            long completed =
                    events.stream()
                            .filter(event -> "run/end".equals(event.type()))
                            .findFirst()
                            .orElseThrow()
                            .seq();
            assertTrue(
                    events.stream()
                            .filter(event -> "model/end".equals(event.type()))
                            .allMatch(event -> event.seq() < completed));
        }
    }
}
