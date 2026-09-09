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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2 in-memory integration tests for {@link JdbcSandboxExecutionGuard} using the
 * {@link TableBasedLockStrategy}.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcSandboxExecutionGuard H2 integration tests")
class JdbcSandboxExecutionGuardH2Test {

    private JdbcSandboxExecutionGuard guard;

    @BeforeEach
    void setUp() {
        DataSource ds = H2TestSupport.createDataSource("guard_test");
        H2Dialect dialect = new H2Dialect();
        dialect.bindDataSource(ds);
        guard =
                JdbcSandboxExecutionGuard.builder(dialect)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
    }

    @Test
    @DisplayName("acquire and release lock succeeds")
    void acquireAndReleaseLock() throws Exception {
        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(IsolationScope.GLOBAL, null, "test-agent")
                        .orElseThrow();

        SandboxLease lease = guard.tryEnter(key);
        assertTrue(lease != null);
        lease.close();
    }

    @Test
    @DisplayName("second acquire after release succeeds")
    void secondAcquireAfterRelease() throws Exception {
        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(IsolationScope.AGENT, null, "agent-1").orElseThrow();

        SandboxLease lease1 = guard.tryEnter(key);
        lease1.close();

        SandboxLease lease2 = guard.tryEnter(key);
        lease2.close();
    }

    @Test
    @DisplayName("lock provides mutual exclusion across threads")
    void lockProvidesMutualExclusion() throws Exception {
        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(IsolationScope.AGENT, null, "mutex-agent")
                        .orElseThrow();
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        int numThreads = 5;
        CountDownLatch allDone = new CountDownLatch(numThreads);
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            pool.submit(
                    () -> {
                        try {
                            SandboxLease lease = guard.tryEnter(key);
                            try {
                                int current = concurrentCount.incrementAndGet();
                                maxConcurrent.accumulateAndGet(current, Math::max);
                                Thread.sleep(100);
                            } finally {
                                concurrentCount.decrementAndGet();
                                lease.close();
                            }
                        } catch (Exception e) {
                            // Lock timeout or interruption — expected for contended threads
                        } finally {
                            allDone.countDown();
                        }
                    });
        }

        assertTrue(allDone.await(30, TimeUnit.SECONDS), "all threads should complete");
        pool.shutdown();
        assertTrue(
                maxConcurrent.get() == 1, "max concurrent should be 1, got " + maxConcurrent.get());
    }

    @Test
    @DisplayName("tryEnter times out with InterruptedException when the lock is held")
    void tryEnterTimesOutWhenHeld() throws Exception {
        // Shared DataSource so both guards contend on the same lock table.
        DataSource ds = H2TestSupport.createDataSource("guard_timeout_test");
        H2Dialect dialect = new H2Dialect();
        dialect.bindDataSource(ds);
        var holder =
                JdbcSandboxExecutionGuard.builder(dialect)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
        var contender =
                JdbcSandboxExecutionGuard.builder(dialect)
                        .lockTimeout(Duration.ofSeconds(1))
                        .build();

        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(IsolationScope.AGENT, null, "held-agent").orElseThrow();

        SandboxLease held = holder.tryEnter(key);
        try {
            assertThrows(InterruptedException.class, () -> contender.tryEnter(key));
        } finally {
            held.close();
        }
    }
}
