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

package io.agentscope.core.legacy.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.core.tracing.NoopTracer;
import io.agentscope.core.tracing.Tracer;
import io.agentscope.core.tracing.TracerRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TracerRegistry Tests")
class TracerRegistryTest {

    @AfterEach
    void tearDown() {
        TracerRegistry.resetToNoop();
    }

    @Test
    @DisplayName("resetToNoop() should shutdown current tracer and restore noop tracer")
    void resetToNoopShouldShutdownCurrentTracer() {
        CloseCountingTracer tracer = new CloseCountingTracer();
        TracerRegistry.register(tracer);

        TracerRegistry.resetToNoop();

        assertEquals(1, tracer.shutdownCount());
        assertInstanceOf(NoopTracer.class, TracerRegistry.get());
    }

    static class CloseCountingTracer implements Tracer {
        private final AtomicInteger shutdownCount = new AtomicInteger();

        @Override
        public void shutdown() {
            shutdownCount.incrementAndGet();
        }

        int shutdownCount() {
            return shutdownCount.get();
        }
    }
}
