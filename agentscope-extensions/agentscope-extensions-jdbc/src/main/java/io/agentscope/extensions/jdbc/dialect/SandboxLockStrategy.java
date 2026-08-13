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
 * Distributed lock contract implemented by {@link AbstractJdbcDialect}.
 *
 * <p>The default implementation in {@link AbstractJdbcDialect#tryEnter} uses a portable
 * table-based lock (INSERT/DELETE with polling). Vendor dialects with native advisory
 * locks override {@code tryEnter} — e.g. {@code MysqlDialect} uses {@code GET_LOCK}.
 *
 * <p>Adding a new database with native lock support requires only overriding
 * {@code tryEnter()} in the vendor dialect class — no separate strategy class needed.
 *
 * @author shanhongyu
 */
public interface SandboxLockStrategy {

    /**
     * Acquires the named lock, blocking until available or timeout expires.
     *
     * @param lockName a unique, validated lock name
     * @param timeoutSeconds maximum wait in seconds
     * @return a lease that releases the lock when closed
     * @throws InterruptedException if the calling thread is interrupted
     */
    SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException;
}
