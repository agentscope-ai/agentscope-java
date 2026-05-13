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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.harness.agent.IsolationScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SandboxIsolationKeyTest {

    private static final String AGENT_ID = "my-agent";

    @Test
    void sessionScope_withSessionKey_resolvesCorrectly() {
        RuntimeContext ctx =
                RuntimeContext.builder().sessionKey(SimpleSessionKey.of("sess-abc")).build();
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(IsolationScope.SESSION, ctx, AGENT_ID);
        assertTrue(key.isPresent());
        assertEquals(IsolationScope.SESSION, key.get().getScope());
        assertEquals("sess-abc", key.get().getValue());
    }

    @Test
    void sessionScope_missingSessionKey_returnsEmpty() {
        RuntimeContext ctx = RuntimeContext.builder().build();
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(IsolationScope.SESSION, ctx, AGENT_ID);
        assertFalse(key.isPresent());
    }

    @Test
    void sessionScope_nullContext_returnsEmpty() {
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(IsolationScope.SESSION, null, AGENT_ID);
        assertFalse(key.isPresent());
    }

    @Test
    void nullScope_treatedAsSession_withSessionKey() {
        RuntimeContext ctx =
                RuntimeContext.builder().sessionKey(SimpleSessionKey.of("sess-def")).build();
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve((IsolationScope) null, ctx, AGENT_ID);
        assertTrue(key.isPresent());
        assertEquals(IsolationScope.SESSION, key.get().getScope());
        assertEquals("sess-def", key.get().getValue());
    }

    @Test
    void userScope_withUserId_resolvesCorrectly() {
        RuntimeContext ctx = RuntimeContext.builder().userId("user-123").build();
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(IsolationScope.USER, ctx, AGENT_ID);
        assertTrue(key.isPresent());
        assertEquals(IsolationScope.USER, key.get().getScope());
        assertEquals("user-123", key.get().getValue());
    }

    @Test
    void userScope_blankUserId_returnsEmpty() {
        RuntimeContext ctx = RuntimeContext.builder().userId("  ").build();
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(IsolationScope.USER, ctx, AGENT_ID);
        assertFalse(key.isPresent());
    }

    @Test
    void userScope_nullUserId_returnsEmpty() {
        RuntimeContext ctx = RuntimeContext.builder().build();
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(IsolationScope.USER, ctx, AGENT_ID);
        assertFalse(key.isPresent());
    }

    @Test
    void userScope_nullContext_returnsEmpty() {
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(IsolationScope.USER, null, AGENT_ID);
        assertFalse(key.isPresent());
    }

    @Test
    void agentScope_alwaysResolvesToAgentId() {
        Optional<SandboxIsolationKey> keyWithCtx =
                SandboxIsolationKey.resolve(
                        IsolationScope.AGENT, RuntimeContext.builder().build(), AGENT_ID);
        Optional<SandboxIsolationKey> keyNullCtx =
                SandboxIsolationKey.resolve(IsolationScope.AGENT, null, AGENT_ID);

        assertTrue(keyWithCtx.isPresent());
        assertEquals(IsolationScope.AGENT, keyWithCtx.get().getScope());
        assertEquals(AGENT_ID, keyWithCtx.get().getValue());

        assertTrue(keyNullCtx.isPresent());
        assertEquals(AGENT_ID, keyNullCtx.get().getValue());
    }

    @Test
    void globalScope_alwaysResolvesToGlobalValue() {
        Optional<SandboxIsolationKey> key =
                SandboxIsolationKey.resolve(IsolationScope.GLOBAL, null, AGENT_ID);
        assertTrue(key.isPresent());
        assertEquals(IsolationScope.GLOBAL, key.get().getScope());
        assertEquals(SandboxIsolationKey.GLOBAL_VALUE, key.get().getValue());
    }

    @Test
    void equalsAndHashCode_sameValues_areEqual() {
        RuntimeContext ctx = RuntimeContext.builder().sessionKey(SimpleSessionKey.of("s1")).build();
        Optional<SandboxIsolationKey> k1 =
                SandboxIsolationKey.resolve(IsolationScope.SESSION, ctx, AGENT_ID);
        Optional<SandboxIsolationKey> k2 =
                SandboxIsolationKey.resolve(IsolationScope.SESSION, ctx, AGENT_ID);
        assertTrue(k1.isPresent() && k2.isPresent());
        assertEquals(k1.get(), k2.get());
        assertEquals(k1.get().hashCode(), k2.get().hashCode());
    }
}
