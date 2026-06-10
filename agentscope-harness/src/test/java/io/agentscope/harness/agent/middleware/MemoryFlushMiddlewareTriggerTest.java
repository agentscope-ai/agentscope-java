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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Focused unit test for {@link MemoryFlushMiddleware#shouldFlushNow} — the trigger gate
 * is the only piece of new logic in the middleware and can be exercised without standing
 * up a full {@code ReActAgent} pipeline.
 */
class MemoryFlushMiddlewareTriggerTest {

    private static MemoryFlushMiddleware make(MemoryConfig.FlushTrigger trigger) {
        // workspaceManager + model are only consumed inside doFlush, which we don't call here
        return new MemoryFlushMiddleware(
                null, null, MemoryFlushManager.DEFAULT_FLUSH_PROMPT, trigger);
    }

    @Test
    void alwaysMode_returnsTrueOnEveryCall() {
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.always());
        for (int i = 0; i < 5; i++) {
            assertTrue(mw.shouldFlushNow(), "ALWAYS should always return true (i=" + i + ")");
        }
    }

    @Test
    void neverMode_returnsFalseOnEveryCall() {
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.never());
        for (int i = 0; i < 5; i++) {
            assertFalse(mw.shouldFlushNow(), "NEVER should always return false (i=" + i + ")");
        }
    }

    @Test
    void throttledMode_firstCallWinsThenBackOff() {
        // 1-hour gap — way larger than the test runtime, so only the first call should pass.
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.throttled(Duration.ofHours(1)));

        assertTrue(mw.shouldFlushNow(), "first call must win the slot");
        assertFalse(mw.shouldFlushNow(), "second call within the window must be throttled");
        assertFalse(mw.shouldFlushNow(), "third call within the window must be throttled");
    }

    @Test
    void throttledMode_smallGapEventuallyReleases() throws InterruptedException {
        Duration gap = Duration.ofMillis(50);
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.throttled(gap));

        assertTrue(mw.shouldFlushNow(), "first call wins");
        assertFalse(mw.shouldFlushNow(), "immediate retry is throttled");

        // Sleep just over the gap so the next call can re-acquire.
        Thread.sleep(gap.toMillis() * 3);

        assertTrue(mw.shouldFlushNow(), "after gap, slot is free again");
        assertFalse(mw.shouldFlushNow(), "immediate retry after the new winner is throttled");
    }

    @Test
    void throttledMode_zeroGapNormalisesToAlways() {
        // FlushTrigger.throttled(Duration.ZERO) is the always() singleton — verify the gate
        // behaves accordingly even when callers pass the zero-Duration form.
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.throttled(Duration.ZERO));
        for (int i = 0; i < 3; i++) {
            assertTrue(mw.shouldFlushNow(), "zero-gap throttling must behave like ALWAYS");
        }
    }
}
