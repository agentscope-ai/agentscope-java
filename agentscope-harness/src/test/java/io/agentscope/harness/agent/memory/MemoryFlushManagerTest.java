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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class MemoryFlushManagerTest {

    @Test
    void flushMemoriesOffloadsWorkspaceIoFromNonBlockingScheduler() {
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        AtomicBoolean readCalled = new AtomicBoolean(false);
        AtomicBoolean readCalledOnNonBlockingThread = new AtomicBoolean(false);
        AtomicBoolean writeCalled = new AtomicBoolean(false);
        AtomicBoolean writeCalledOnNonBlockingThread = new AtomicBoolean(false);

        when(workspaceManager.readManagedWorkspaceFileUtf8(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            readCalled.set(true);
                            readCalledOnNonBlockingThread.set(
                                    readCalledOnNonBlockingThread.get()
                                            || Schedulers.isInNonBlockingThread());
                            return "";
                        });
        doAnswer(
                        invocation -> {
                            writeCalled.set(true);
                            writeCalledOnNonBlockingThread.set(
                                    writeCalledOnNonBlockingThread.get()
                                            || Schedulers.isInNonBlockingThread());
                            return null;
                        })
                .when(workspaceManager)
                .appendUtf8WorkspaceRelative(any(), anyString(), anyString());

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("- extracted memory")
                                                                        .build()))
                                                .build())
                                .subscribeOn(Schedulers.parallel()));

        MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, model);
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Remember this fact").build())
                                .build());

        Mono.defer(() -> flushManager.flushMemories(RuntimeContext.empty(), messages))
                .subscribeOn(Schedulers.parallel())
                .block();

        assertTrue(readCalled.get());
        assertTrue(writeCalled.get());
        assertFalse(readCalledOnNonBlockingThread.get());
        assertFalse(writeCalledOnNonBlockingThread.get());
    }
}
