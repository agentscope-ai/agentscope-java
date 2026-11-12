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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class DefaultContextStoreTest {

    interface SharedContext {}

    static class BaseContext implements SharedContext {}

    static class AgentContext extends BaseContext {}

    static class OtherAgentContext extends BaseContext {}

    @Test
    void retrievesSingletonBySuperclassAndInterface() {
        AgentContext value = new AgentContext();
        ContextStore store = DefaultContextStore.builder().register(value).build();

        assertSame(value, store.get(BaseContext.class));
        assertSame(value, store.get(SharedContext.class));
        assertTrue(store.contains(BaseContext.class));
        assertTrue(store.contains(SharedContext.class));
        assertTrue(store.contains("", SharedContext.class));
    }

    @Test
    void keyedLookupPreservesKeyIsolation() {
        AgentContext admin = new AgentContext();
        AgentContext guest = new AgentContext();
        ContextStore store =
                DefaultContextStore.builder()
                        .register("admin", admin)
                        .register("guest", guest)
                        .build();

        assertSame(admin, store.get("admin", BaseContext.class));
        assertSame(guest, store.get("guest", SharedContext.class));
        assertTrue(store.contains("admin", SharedContext.class));
        assertTrue(store.contains(BaseContext.class));
        assertNull(store.get(BaseContext.class));
        assertNull(store.get("missing", BaseContext.class));
        assertFalse(store.contains("missing", SharedContext.class));
        assertFalse(store.contains("", SharedContext.class));
    }

    @Test
    void exactTypeWinsAndMissingExactKeyFallsBack() {
        BaseContext exact = new BaseContext();
        AgentContext fallback = new AgentContext();
        ContextStore store =
                DefaultContextStore.builder()
                        .register(exact)
                        .register(fallback)
                        .register("exact", exact)
                        .register("exact", fallback)
                        .register("fallback", fallback)
                        .build();

        assertSame(exact, store.get(BaseContext.class));
        assertSame(exact, store.get("exact", BaseContext.class));
        assertSame(fallback, store.get("fallback", BaseContext.class));
        assertTrue(store.contains("fallback", BaseContext.class));
    }

    @Test
    void multipleAssignableTypesReturnAMatchingInstance() {
        AgentContext first = new AgentContext();
        OtherAgentContext second = new OtherAgentContext();
        ContextStore store = DefaultContextStore.builder().register(first).register(second).build();

        SharedContext result = store.get(SharedContext.class);
        assertTrue(result == first || result == second);
        assertTrue(store.contains(SharedContext.class));
    }

    @Test
    void unrelatedOrNarrowerTypesDoNotMatch() {
        ContextStore store = DefaultContextStore.builder().register(new BaseContext()).build();

        assertNull(store.get(AgentContext.class));
        assertFalse(store.contains(AgentContext.class));
        assertNull(store.get(String.class));
        assertFalse(store.contains("", String.class));
    }

    @Test
    void explicitRegisteredTypeDefinesLookupBoundary() {
        AgentContext value = new AgentContext();
        ContextStore store =
                DefaultContextStore.builder().register(BaseContext.class, value).build();

        assertSame(value, store.get(SharedContext.class));
        assertNull(store.get(AgentContext.class));
        assertFalse(store.contains(AgentContext.class));
    }

    @Test
    void toolExecutionContextExposesAssignableLookup() {
        AgentContext value = new AgentContext();
        ToolExecutionContext context = ToolExecutionContext.builder().register(value).build();

        assertSame(value, context.get(BaseContext.class));
        assertTrue(context.contains(SharedContext.class));
    }
}
