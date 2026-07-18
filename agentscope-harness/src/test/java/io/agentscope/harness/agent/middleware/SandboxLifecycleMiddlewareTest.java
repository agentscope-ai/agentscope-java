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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxBindingKey;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class SandboxLifecycleMiddlewareTest {

    private static RuntimeContext ctxWithSandbox(String sessionId) {
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .put(SandboxContext.class, SandboxContext.builder().build())
                .build();
    }

    private static SandboxManager mockManager(AtomicInteger acquireCount) throws Exception {
        SandboxManager manager = mock(SandboxManager.class);
        when(manager.acquire(any(), any()))
                .thenAnswer(
                        inv -> {
                            acquireCount.incrementAndGet();
                            return SandboxAcquireResult.selfManaged(new NoopSandbox());
                        });
        doNothing().when(manager).persistState(any(), any(), any());
        doNothing().when(manager).release(any());
        return manager;
    }

    @Test
    void acquireForCall_differentSessions_runConcurrently() throws Exception {
        AtomicInteger acquireCount = new AtomicInteger();
        CountDownLatch bothAcquired = new CountDownLatch(2);

        SandboxManager manager = mockManager(acquireCount);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        SandboxLifecycleMiddleware mw = new SandboxLifecycleMiddleware(manager, fs);

        RuntimeContext ctxA = ctxWithSandbox("session-a");
        RuntimeContext ctxB = ctxWithSandbox("session-b");

        CountDownLatch done = new CountDownLatch(2);

        Mono.using(
                        () -> {
                            mw.acquireForCall(ctxA);
                            return ctxA;
                        },
                        ctx ->
                                Mono.fromCallable(
                                        () -> {
                                            bothAcquired.countDown();
                                            bothAcquired.await(3, TimeUnit.SECONDS);
                                            return new AssistantMessage("a");
                                        }),
                        ctx -> mw.releaseForCall(ctx))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(s -> done.countDown())
                .subscribe();

        Mono.using(
                        () -> {
                            mw.acquireForCall(ctxB);
                            return ctxB;
                        },
                        ctx ->
                                Mono.fromCallable(
                                        () -> {
                                            bothAcquired.countDown();
                                            bothAcquired.await(3, TimeUnit.SECONDS);
                                            return new AssistantMessage("b");
                                        }),
                        ctx -> mw.releaseForCall(ctx))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(s -> done.countDown())
                .subscribe();

        assertTrue(bothAcquired.await(3, TimeUnit.SECONDS));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, acquireCount.get());
    }

    @Test
    void acquireForCall_releaseForCall_bindsAndUnbindsSandbox() throws Exception {
        AtomicInteger acquireCount = new AtomicInteger();
        SandboxManager manager = mockManager(acquireCount);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        SandboxLifecycleMiddleware mw = new SandboxLifecycleMiddleware(manager, fs);

        RuntimeContext ctx = ctxWithSandbox("solo-session");
        String expectedKey = SandboxBindingKey.resolve(ctx);

        mw.acquireForCall(ctx);
        assertEquals(1, acquireCount.get());
        assertNotNull(fs.getSandbox(expectedKey));

        mw.releaseForCall(ctx);
        verify(manager).release(any());
        // sandbox is unbound after release
        assertNull(fs.getSandbox(expectedKey));
    }

    private static class NoopSandbox implements Sandbox {
        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return null;
        }

        @Override
        public ExecResult exec(RuntimeContext rc, String cmd, Integer timeout) {
            return new ExecResult(0, "", "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }
}
