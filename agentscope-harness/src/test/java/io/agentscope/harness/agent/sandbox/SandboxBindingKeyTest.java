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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

class SandboxBindingKeyTest {

    @Test
    void resolve_nullContext_returnsNull() {
        assertNull(SandboxBindingKey.resolve(null));
    }

    @Test
    void resolve_sessionIdAbsent_returnsNull() {
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").build();
        assertNull(SandboxBindingKey.resolve(ctx));
    }

    @Test
    void resolve_userIdAbsent_usesAnonymous() {
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").build();
        assertEquals("__anon__/s1", SandboxBindingKey.resolve(ctx));
    }

    @Test
    void resolve_bothPresent_compositeKey() {
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        assertEquals("u1/s1", SandboxBindingKey.resolve(ctx));
    }
}
