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
package io.agentscope.extensions.jdbc.sandbox;

import io.agentscope.extensions.jdbc.dialect.SandboxLockStrategy;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-backed {@link SandboxExecutionGuard} that delegates all lock semantics to a
 * {@link SandboxLockStrategy} provided by the dialect.
 *
 * <p>This component contains zero database-specific logic — the dialect handles
 * whether to use MySQL {@code GET_LOCK} or the portable table-based lock
 * (default in {@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect}).
 *
 * <p>Each lock is identified by a string key derived from the
 * {@link SandboxIsolationKey}'s scope and value.
 *
 * @author shanhongyu
 */
public final class JdbcSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(JdbcSandboxExecutionGuard.class);

    private static final String DEFAULT_KEY_PREFIX = "agentscope:sandbox:lock:";

    private final SandboxLockStrategy strategy;
    private final String keyPrefix;
    private final int lockTimeoutSeconds;

    private JdbcSandboxExecutionGuard(Builder builder) {
        this.strategy = builder.strategy;
        this.keyPrefix = builder.keyPrefix;
        this.lockTimeoutSeconds = builder.lockTimeoutSeconds;
    }

    /**
     * Creates a builder for {@link JdbcSandboxExecutionGuard}.
     *
     * @param strategy the lock strategy from the dialect
     * @return a new builder
     */
    public static Builder builder(SandboxLockStrategy strategy) {
        return new Builder(strategy);
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String lockName = composeLockName(key);
        log.debug("[sandbox-guard] Acquiring lock: {}", lockName);
        return strategy.tryEnter(lockName, lockTimeoutSeconds);
    }

    private String composeLockName(SandboxIsolationKey key) {
        return keyPrefix + key.getScope().name().toLowerCase() + ":" + key.getValue();
    }

    /** Builder for {@link JdbcSandboxExecutionGuard}. */
    public static final class Builder {

        private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 1800; // 30 minutes

        private final SandboxLockStrategy strategy;
        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private int lockTimeoutSeconds = DEFAULT_LOCK_TIMEOUT_SECONDS;

        private Builder(SandboxLockStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        }

        /** Sets a custom key prefix for the lock name. */
        public Builder keyPrefix(String keyPrefix) {
            if (keyPrefix == null || keyPrefix.isBlank()) {
                throw new IllegalArgumentException("keyPrefix must not be blank");
            }
            this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
            return this;
        }

        /** Sets the lock timeout duration. */
        public Builder lockTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.lockTimeoutSeconds = (int) timeout.toSeconds();
            return this;
        }

        /** Builds the {@link JdbcSandboxExecutionGuard}. */
        public JdbcSandboxExecutionGuard build() {
            return new JdbcSandboxExecutionGuard(this);
        }
    }
}
