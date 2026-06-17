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
package io.agentscope.harness.agent.bus;

import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Registry for tracking async tool executions that have been offloaded to the background.
 *
 * <p>When a tool execution exceeds the configured timeout, {@code AsyncToolMiddleware} registers
 * it here. The registry tracks the tool's lifecycle so that:
 * <ul>
 *   <li>On normal completion, the record is updated and the result delivered via inbox</li>
 *   <li>On process crash/restart, stale RUNNING records can be detected and cleaned up</li>
 * </ul>
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link InMemoryAsyncToolRegistry} — single-process, suitable for testing</li>
 * </ul>
 */
public interface AsyncToolRegistry {

    /**
     * Register a new async tool execution.
     *
     * @param record the async tool record with status {@link AsyncToolRecord#RUNNING}
     */
    Mono<Void> register(AsyncToolRecord record);

    /**
     * Mark an async tool execution as completed.
     *
     * @param id     the async tool record id
     * @param result the tool execution result text
     */
    Mono<Void> complete(String id, String result);

    /**
     * Mark an async tool execution as failed.
     *
     * @param id    the async tool record id
     * @param error the error message
     */
    Mono<Void> fail(String id, String error);

    /**
     * Find async tool records for the given session that have been in RUNNING status longer
     * than the specified TTL. These are likely orphaned due to process crash.
     *
     * @param sessionId the session to check
     * @param ttl       records older than this duration are considered stale
     * @return stale RUNNING records
     */
    Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl);

    /**
     * Mark a stale async tool record as timed out. Prevents it from being returned by
     * subsequent {@link #findStale} calls.
     *
     * @param id the async tool record id
     */
    Mono<Void> markTimeout(String id);
}
