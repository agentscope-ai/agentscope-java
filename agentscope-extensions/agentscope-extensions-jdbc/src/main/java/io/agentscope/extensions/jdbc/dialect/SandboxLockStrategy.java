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
package io.agentscope.extensions.jdbc.dialect;

import io.agentscope.harness.agent.sandbox.SandboxLease;

/**
 * Portable abstraction over a database-backed distributed lock.
 *
 * <p>Databases with native advisory-lock support (MySQL {@code GET_LOCK},
 * PostgreSQL {@code pg_advisory_lock}) provide their own strategy implementation.
 * Databases without native locks use {@link TableBasedLockStrategy} as a
 * portable fallback.
 *
 * @author shanhongyu
 * @see TableBasedLockStrategy
 */
public interface SandboxLockStrategy {

    /**
     * Acquires the named lock, blocking until the lock is available or the timeout
     * expires.
     *
     * @param lockName a unique, validated lock name
     * @param timeoutSeconds maximum seconds to wait before giving up
     * @return a lease that releases the lock when closed
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException;
}
