/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes immutable confirmed outcomes until the Tool consumer commits them, without rerunning tools. */
public final class ManagedToolResultPublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedToolResultPublisher.class);
    private final ManagedToolWorkStore journal;
    private final ManagedMessageBus bus;
    private final ManagedBatchCodec codec;
    private ScheduledExecutorService worker;

    public ManagedToolResultPublisher(
            ManagedToolWorkStore journal, ManagedMessageBus bus, ManagedBatchCodec codec) {
        this.journal = journal;
        this.bus = bus;
        this.codec = codec;
    }

    public void publish(ManagedToolResult result) {
        if (!journal.stageToolResult(result)) return;
        journal.complete(result);
        flush(result.scope(), result.taskId());
    }

    /** Preserve a confirmed outcome before draining remaining output or doing transport I/O. */
    public void capture(ManagedToolResult result) {
        journal.stageToolResult(result);
    }

    public int flush(ManagedScope scope, String task) {
        int sent = 0;
        for (int i = 0; i < 64; i++) {
            var next = journal.toolEvents().publication(scope, task, System.currentTimeMillis());
            if (next.isEmpty()) break;
            var event = next.get();
            journal.toolEvents().attempt(event, System.currentTimeMillis() + 1000);
            bus.publish(Channel.TOOL_EVENTS, codec.encode(event));
            journal.toolEvents().published(event);
            sent++;
        }
        return sent;
    }

    private void retry(ManagedToolResult result) {
        journal.toolResultPublishAttempt(result, System.currentTimeMillis() + 1000);
    }

    /** A restarted worker must use the captured result before consulting an external execution. */
    public boolean publishStored(ManagedScope scope, String task) {
        var captured = journal.stagedToolResult(scope, task);
        if (captured.isEmpty()) return false;
        retry(captured.get());
        var output = journal.toolEvents().cursor(scope, task);
        if (output.isPresent() && !output.get().captureComplete()) return false;
        publish(captured.get());
        ManagedRuntimeLog.info(
                log,
                "tool_worker.tool.stored_result.reused",
                scope,
                null,
                "task_id",
                task,
                "execution_id",
                captured.get().executionId(),
                "status",
                captured.get().status());
        return true;
    }

    public int repair(int limit) {
        int sent = 0;
        for (var ref : journal.pendingToolResultPublications(limit, System.currentTimeMillis())) {
            try {
                if (publishStored(ref.scope(), ref.taskId())) sent++;
            } catch (RuntimeException unavailable) {
                log.warn(
                        "Tool result publication pending: task={}, cause={}",
                        ref.taskId(),
                        unavailable.toString());
            }
        }
        for (var ref : journal.toolEvents().pending(limit, System.currentTimeMillis())) {
            try {
                sent += flush(ref.scope(), ref.taskId());
            } catch (RuntimeException unavailable) {
                log.warn(
                        "Tool source publication pending: task={}, cause={}",
                        ref.taskId(),
                        unavailable.toString());
            }
        }
        return sent;
    }

    public synchronized void start() {
        if (worker != null) return;
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(
                () -> {
                    try {
                        repair(64);
                    } catch (RuntimeException unavailable) {
                        log.warn("Tool result outbox unavailable: {}", unavailable.toString());
                    }
                },
                0,
                500,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void close() {
        if (worker != null) worker.shutdownNow();
    }
}
