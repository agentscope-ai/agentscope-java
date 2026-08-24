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
package io.agentscope.harness.agent.subagent.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BackgroundTaskTest {

    @Test
    void cancel_invokesActionAndAttachedRunnerExactlyOnce() {
        CompletableFuture<String> completion = new CompletableFuture<>();
        AtomicInteger actionCalls = new AtomicInteger();
        RecordingFuture runner = new RecordingFuture();
        BackgroundTask task =
                new BackgroundTask("task-1", "agent-1", completion, actionCalls::incrementAndGet);
        task.attachExecutionFuture(runner);

        assertTrue(task.cancel(true));
        assertFalse(task.cancel(true));

        assertEquals(1, actionCalls.get());
        assertEquals(1, runner.cancelCalls.get());
        assertTrue(runner.mayInterrupt.get());
        assertTrue(task.isCancellationRequested());
        assertEquals(TaskStatus.CANCELLED, task.getTaskStatus());
    }

    @Test
    void attachExecutionFuture_afterCancelCancelsRunnerImmediately() {
        CompletableFuture<String> completion = new CompletableFuture<>();
        BackgroundTask task = new BackgroundTask("task-2", "agent-2", completion);

        assertTrue(task.cancel(true));
        RecordingFuture runner = new RecordingFuture();
        task.attachExecutionFuture(runner);

        assertEquals(1, runner.cancelCalls.get());
        assertTrue(runner.mayInterrupt.get());
    }

    @Test
    void cancel_afterCompletionDoesNotInvokeActionOrRunner() {
        CompletableFuture<String> completion = CompletableFuture.completedFuture("done");
        AtomicInteger actionCalls = new AtomicInteger();
        RecordingFuture runner = new RecordingFuture();
        BackgroundTask task =
                new BackgroundTask("task-3", "agent-3", completion, actionCalls::incrementAndGet);
        task.attachExecutionFuture(runner);

        assertFalse(task.cancel(true));
        assertEquals(0, actionCalls.get());
        assertEquals(0, runner.cancelCalls.get());
        assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
    }

    @Test
    void cancel_actionFailureDoesNotPreventRunnerCancellation() {
        CompletableFuture<String> completion = new CompletableFuture<>();
        RecordingFuture runner = new RecordingFuture();
        BackgroundTask task =
                new BackgroundTask(
                        "task-4",
                        "agent-4",
                        completion,
                        () -> {
                            throw new IllegalStateException("cancel hook failed");
                        });
        task.attachExecutionFuture(runner);

        assertTrue(assertDoesNotThrow(() -> task.cancel(true)));
        assertEquals(1, runner.cancelCalls.get());
        assertTrue(runner.mayInterrupt.get());
        assertEquals(TaskStatus.CANCELLED, task.getTaskStatus());
    }

    private static final class RecordingFuture implements Future<Object> {
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicBoolean mayInterrupt = new AtomicBoolean();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            mayInterrupt.set(mayInterruptIfRunning);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls.get() > 0;
        }

        @Override
        public boolean isDone() {
            return isCancelled();
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
}
