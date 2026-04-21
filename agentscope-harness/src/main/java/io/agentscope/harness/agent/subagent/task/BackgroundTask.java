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

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps a {@link CompletableFuture} to track background subagent task execution with status,
 * result, error, and lifecycle metadata. Thread-safe by delegation to the underlying future.
 *
 * <p>Lifecycle metadata:
 *
 * <ul>
 *   <li>{@code agentId} — which subagent type is executing
 *   <li>{@code createdAt} — when the task was created
 *   <li>{@code lastCheckedAt} — when the task status was last polled
 * </ul>
 */
public class BackgroundTask {

    private final String taskId;
    private final String agentId;
    private final CompletableFuture<String> future;
    private final Instant createdAt;
    private volatile Instant lastCheckedAt;
    private volatile boolean cancelled;

    public BackgroundTask(String taskId, String agentId, CompletableFuture<String> future) {
        this.taskId = taskId;
        this.agentId = agentId;
        this.future = future;
        this.createdAt = Instant.now();
        this.lastCheckedAt = this.createdAt;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getAgentId() {
        return agentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void updateLastCheckedAt() {
        this.lastCheckedAt = Instant.now();
    }

    public boolean isCompleted() {
        return future.isDone();
    }

    /**
     * Returns the structured {@link TaskStatus} for this task, reflecting the underlying future
     * state and any explicit cancellation.
     */
    public TaskStatus getTaskStatus() {
        if (cancelled || future.isCancelled()) {
            return TaskStatus.CANCELLED;
        }
        if (future.isCompletedExceptionally()) {
            return TaskStatus.FAILED;
        }
        if (future.isDone()) {
            return TaskStatus.COMPLETED;
        }
        return TaskStatus.RUNNING;
    }

    /** Returns a human-readable status string. */
    public String getStatus() {
        TaskStatus ts = getTaskStatus();
        if (ts == TaskStatus.FAILED) {
            Exception error = getError();
            return "Failed: " + (error != null ? error.getMessage() : "Unknown error");
        }
        return ts.name().charAt(0) + ts.name().substring(1).toLowerCase();
    }

    /** Non-blocking result peek; returns null if not yet completed or if an error occurred. */
    public String getResult() {
        try {
            return future.getNow(null);
        } catch (Exception e) {
            return null;
        }
    }

    public Exception getError() {
        if (future.isCompletedExceptionally()) {
            try {
                future.getNow(null);
            } catch (Exception e) {
                return (e.getCause() instanceof Exception cause) ? cause : e;
            }
        }
        return null;
    }

    /**
     * Blocks until the task completes or the timeout elapses.
     *
     * @return true if completed within timeout, false if timed out
     */
    public boolean waitForCompletion(long timeoutMs) throws InterruptedException {
        if (future.isDone()) {
            return true;
        }
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            throw e;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Cancels the task. Sets the internal cancelled flag so that {@link #getTaskStatus()} returns
     * {@link TaskStatus#CANCELLED} even if the future cannot be interrupted.
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        this.cancelled = true;
        return future.cancel(mayInterruptIfRunning);
    }
}
