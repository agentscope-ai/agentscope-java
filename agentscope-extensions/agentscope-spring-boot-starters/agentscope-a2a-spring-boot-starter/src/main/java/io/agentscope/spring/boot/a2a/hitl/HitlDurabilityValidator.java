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
package io.agentscope.spring.boot.a2a.hitl;

import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBinding;
import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBindingContract;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityVerification;
import io.agentscope.core.a2a.server.hitl.HitlServerProperties;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fail-fast validation for the durable A2A HITL control plane. */
public final class HitlDurabilityValidator {

    private static final Logger log = LoggerFactory.getLogger(HitlDurabilityValidator.class);

    private static final String MISSING_BINDING_MESSAGE =
            "Durable HITL requires exactly one A2aHitlDurabilityBinding. "
                    + "If you use a Redis coordination provider, check whether "
                    + "agentscope.a2a.server.hitl.coordination-provider=redis is missing.";

    private HitlDurabilityValidator() {}

    /** Validates durable mode and returns the provider's safe coordination identity. */
    public static HitlDurabilityVerification validate(
            HitlServerProperties properties, AgentRunner runner, A2aHitlDurabilityBinding binding) {
        if (properties == null
                || !properties.enabled()
                || properties.durability() != HitlServerProperties.Durability.DURABLE) {
            return null;
        }
        if (binding == null) {
            throw failure(MISSING_BINDING_MESSAGE);
        }
        Objects.requireNonNull(runner, "runner");
        if (runner.hitlDurabilityCapability() != HitlDurabilityCapability.DURABLE) {
            throw failure("runner must explicitly declare DURABLE resume capability");
        }
        AgentStateStore stateStore =
                runner.actualAgentStateStore()
                        .orElseThrow(
                                () -> failure("runner did not expose its actual AgentStateStore"));
        if (stateStore instanceof InMemoryAgentStateStore
                || stateStore instanceof JsonFileAgentStateStore) {
            throw failure("AgentStateStore must be shared, not InMemory or JsonFile");
        }

        HitlDurabilityVerification verification = A2aHitlDurabilityBindingContract.verify(binding);
        log.info(
                "A2A_HITL_CONTROL_PLANE_DURABLE_OK coordinationProvider={} coordinationStoreId={}",
                verification.coordinationProvider(),
                verification.coordinationStoreId());
        log.info(
                "A2A_HITL_AGENT_STATE_DECLARED storeType={}"
                        + " crossReplicaReachability=application-responsibility",
                stateStore.getClass().getName());
        return verification;
    }

    public static String missingBindingMessage() {
        return MISSING_BINDING_MESSAGE;
    }

    private static IllegalStateException failure(String detail) {
        return new IllegalStateException("Durable A2A HITL wiring check failed: " + detail);
    }
}
