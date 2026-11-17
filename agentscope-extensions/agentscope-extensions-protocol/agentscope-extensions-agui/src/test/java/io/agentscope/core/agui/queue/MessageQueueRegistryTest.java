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
package io.agentscope.core.agui.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MessageQueueRegistryTest {

    @Test
    void shouldReturnSameQueueForSameThreadAndDifferentForOthers() {
        MessageQueueRegistry registry = new MessageQueueRegistry(16, 64);

        assertSame(registry.forThread("t1"), registry.forThread("t1"));
        assertNotSame(registry.forThread("t1"), registry.forThread("t2"));
        assertEquals(2, registry.size());
    }

    @Test
    void shouldRejectBlankThreadId() {
        MessageQueueRegistry registry = new MessageQueueRegistry(16, 64);

        assertThrows(IllegalArgumentException.class, () -> registry.forThread(" "));
        assertThrows(IllegalArgumentException.class, () -> registry.forThread(null));
    }
}
