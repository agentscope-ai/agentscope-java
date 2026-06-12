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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ConversationCompactorTest {

    @Test
    void compactIfNeededOffloadsBlockingStagesFromNonBlockingScheduler() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("summary")
                                                                        .build()))
                                                .build())
                                .subscribeOn(Schedulers.parallel()));

        RecordingMemoryFlushManager flushManager = new RecordingMemoryFlushManager(model);
        ConversationCompactor compactor = new ConversationCompactor(model, flushManager);

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("first").build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("second").build())
                                .build());

        CompactionConfig config =
                CompactionConfig.builder().triggerMessages(1).keepMessages(1).build();

        Optional<List<Msg>> result =
                Mono.defer(
                                () ->
                                        compactor.compactIfNeeded(
                                                RuntimeContext.empty(),
                                                messages,
                                                config,
                                                "agent",
                                                "session"))
                        .subscribeOn(Schedulers.parallel())
                        .block();

        assertTrue(result != null && result.isPresent());
        assertTrue(flushManager.flushCalled());
        assertTrue(flushManager.offloadCalled());
        assertTrue(flushManager.flushCalledOnNonBlockingThread());
        assertFalse(flushManager.offloadCalledOnNonBlockingThread());
        assertFalse(flushManager.resolvePathCalledOnNonBlockingThread());
    }

    private static final class RecordingMemoryFlushManager extends MemoryFlushManager {

        private final AtomicBoolean flushCalled = new AtomicBoolean(false);
        private final AtomicBoolean flushCalledOnNonBlockingThread = new AtomicBoolean(false);
        private final AtomicBoolean offloadCalled = new AtomicBoolean(false);
        private final AtomicBoolean offloadCalledOnNonBlockingThread = new AtomicBoolean(false);
        private final AtomicBoolean resolvePathCalledOnNonBlockingThread = new AtomicBoolean(false);

        RecordingMemoryFlushManager(Model model) {
            super(mock(WorkspaceManager.class), model);
        }

        @Override
        public Mono<Void> flushMemories(RuntimeContext rc, List<Msg> messages) {
            return Mono.<Void>fromRunnable(
                            () -> {
                                flushCalled.set(true);
                                flushCalledOnNonBlockingThread.set(
                                        Schedulers.isInNonBlockingThread());
                            })
                    .subscribeOn(Schedulers.parallel());
        }

        @Override
        public void offloadMessages(
                RuntimeContext rc, List<Msg> messages, String agentId, String sessionId) {
            offloadCalled.set(true);
            offloadCalledOnNonBlockingThread.set(Schedulers.isInNonBlockingThread());
        }

        @Override
        public String resolveOffloadPath(RuntimeContext rc, String agentId, String sessionId) {
            resolvePathCalledOnNonBlockingThread.set(Schedulers.isInNonBlockingThread());
            return "";
        }

        boolean flushCalled() {
            return flushCalled.get();
        }

        boolean flushCalledOnNonBlockingThread() {
            return flushCalledOnNonBlockingThread.get();
        }

        boolean offloadCalled() {
            return offloadCalled.get();
        }

        boolean offloadCalledOnNonBlockingThread() {
            return offloadCalledOnNonBlockingThread.get();
        }

        boolean resolvePathCalledOnNonBlockingThread() {
            return resolvePathCalledOnNonBlockingThread.get();
        }
    }
}
