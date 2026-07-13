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
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.agent.RuntimeContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HarnessGateway#injectBusinessContext} — the merge step that copies
 * caller-supplied business parameters from {@link MsgContext#businessContext()} into the
 * per-call {@link RuntimeContext}.
 *
 * <p>Guards two invariants:
 *
 * <ul>
 *   <li>Business entries whose key collides with a framework-owned RuntimeContext key are
 *       dropped rather than allowed to overwrite framework values already staged in the builder.
 *   <li>Non-colliding entries pass through unchanged, including {@code null} values.
 * </ul>
 */
class HarnessGatewayInjectBusinessContextTest {

    private static MsgContext contextWithBusiness(Map<String, Object> business) {
        return new MsgContext(
                "chatui", null, null, null, null, Map.of("agentId", "main"), null, business);
    }

    @Test
    void injectBusinessContext_reservedKeys_areDroppedAndDoNotOverwriteFrameworkValues() {
        Object frameworkMsgContext = new Object();
        Object frameworkGateKey = "gw-key";
        Object frameworkOutbound = new Object();

        RuntimeContext.Builder builder =
                RuntimeContext.builder()
                        .put("msgContext", frameworkMsgContext)
                        .put("gateKey", frameworkGateKey)
                        .put("outboundAddress", frameworkOutbound);

        Map<String, Object> business = new LinkedHashMap<>();
        business.put("msgContext", "attacker");
        business.put("gateKey", "attacker");
        business.put("outboundAddress", "attacker");
        business.put("tenantId", "tenant-123");

        HarnessGateway.injectBusinessContext(builder, contextWithBusiness(business));

        RuntimeContext rc = builder.build();
        assertEquals(frameworkMsgContext, rc.get("msgContext"));
        assertEquals(frameworkGateKey, rc.get("gateKey"));
        assertEquals(frameworkOutbound, rc.get("outboundAddress"));
        assertEquals("tenant-123", rc.get("tenantId"));
    }

    @Test
    void injectBusinessContext_emptyOrNullBusinessContext_leavesFrameworkValuesIntact() {
        RuntimeContext.Builder builder = RuntimeContext.builder().put("gateKey", "gw-key");

        HarnessGateway.injectBusinessContext(builder, contextWithBusiness(Map.of()));

        RuntimeContext rc = builder.build();
        assertEquals("gw-key", rc.get("gateKey"));
        assertNull(rc.get("tenantId"));
    }

    @Test
    void injectBusinessContext_nonReservedKeys_areCopiedThrough() {
        RuntimeContext.Builder builder = RuntimeContext.builder();

        Map<String, Object> business =
                Map.of("tenantId", "tenant-123", "modelConfigId", "gpt-4", "maxTokens", 4096);

        HarnessGateway.injectBusinessContext(builder, contextWithBusiness(business));

        RuntimeContext rc = builder.build();
        assertEquals("tenant-123", rc.get("tenantId"));
        assertEquals("gpt-4", rc.get("modelConfigId"));
        assertEquals(4096, (Integer) rc.get("maxTokens"));
    }

    @Test
    void reservedKeySet_matchesFrameworkPutCallsInHarnessGatewayRunMethods() {
        // Guards against silent drift between what run()/runStream() put into RuntimeContext and
        // what injectBusinessContext protects. Update RESERVED_RUNTIME_CONTEXT_KEYS whenever a new
        // framework-owned key is added.
        assertNotNull(HarnessGateway.RESERVED_RUNTIME_CONTEXT_KEYS);
        assertEquals(3, HarnessGateway.RESERVED_RUNTIME_CONTEXT_KEYS.size());
        assertEquals(
                java.util.Set.of("msgContext", "gateKey", "outboundAddress"),
                HarnessGateway.RESERVED_RUNTIME_CONTEXT_KEYS);
    }
}
