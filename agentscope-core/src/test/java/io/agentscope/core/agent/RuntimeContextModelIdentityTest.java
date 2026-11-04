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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link RuntimeContext#getModelIdentity()} storage and derivation semantics. */
@DisplayName("RuntimeContext modelIdentity")
class RuntimeContextModelIdentityTest {

    @Test
    @DisplayName("builder stores the modelIdentity; default is null")
    void builderStoresModelId() {
        assertNull(RuntimeContext.builder().build().getModelIdentity());
        assertEquals(
                "deepseek:v4",
                RuntimeContext.builder().modelIdentity("deepseek:v4").build().getModelIdentity());
    }

    @Test
    @DisplayName("derived contexts keep the modelIdentity (internal per-run derivations need it)")
    void derivedContextKeepsModelId() {
        RuntimeContext parent =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .modelIdentity("deepseek:v4")
                        .put("kept", "attribute")
                        .build();
        RuntimeContext derived = RuntimeContext.builder(parent).build();
        assertEquals("deepseek:v4", derived.getModelIdentity());
        assertEquals("u1", derived.getUserId());
        assertEquals("s1", derived.getSessionId());
        assertEquals(parent.getRunId(), derived.getRunId());
        assertEquals("attribute", derived.get("kept"));
        assertEquals("deepseek:v4", parent.getModelIdentity(), "source is unaffected");
    }

    @Test
    @DisplayName("a derived context can clear the modelIdentity (subagent boundary semantics)")
    void derivedContextCanClearModelId() {
        RuntimeContext parent =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .modelIdentity("deepseek:v4")
                        .build();
        RuntimeContext child = RuntimeContext.builder(parent).modelIdentity(null).build();
        assertNull(child.getModelIdentity(), "subagents keep their build-time model contract");
        assertEquals("deepseek:v4", parent.getModelIdentity(), "source is unaffected");
    }

    @Test
    @DisplayName("modelIdentity is not an attribute and stays invisible to the attribute bag")
    void modelIdentityIsNotAnAttribute() {
        RuntimeContext ctx = RuntimeContext.builder().modelIdentity("m").build();
        assertNull(ctx.get("modelIdentity"));
        assertEquals(Map.of(), ctx.getExtra());
    }
}
